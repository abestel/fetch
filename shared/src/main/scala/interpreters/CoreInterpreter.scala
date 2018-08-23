/*
 * Copyright 2016-2018 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fetch
package interpreters

import scala.collection.immutable._

import cats.effect.IO
import cats.{~>, Monad, MonadError}
import cats.data.{Ior, IorNel, NonEmptyList, StateT, Validated}
import cats.free.Free
import cats.implicits._

object CoreInterpreter {

  def apply[M[_]]: FetchOp ~> FetchInterpreter[M]#f =
    new (FetchOp ~> FetchInterpreter[M]#f) {
      def apply[A](fa: FetchOp[A]): FetchInterpreter[M]#f[A] =
        fa match {
          case Join(fl, fr) =>
            Monad[FetchInterpreter[M]#f].tuple2(
              fl.foldMap[FetchInterpreter[M]#f](this),
              fr.foldMap[FetchInterpreter[M]#f](this))

          case other =>
            StateT[IO, FetchEnv, A] { env: FetchEnv =>
              other match {
                case Thrown(e)              => IO.raiseError(UnhandledException(e))
                case one @ FetchOne(_, _)   => processOne(one, env)
                case many @ FetchMany(_, _) => processMany(many, env)
                case conc @ Concurrent(_)   => processConcurrent(conc, env)
                case Join(_, _)             => throw new Exception("join already handled")
              }
            }
        }
    }

  private[this] def processOne[M[_], A](
      one: FetchOne[Any, A],
      env: FetchEnv
  ): IO[(FetchEnv, A)] = {
    val FetchOne(id, ds) = one
    val startRound       = System.nanoTime()
    env.cache
      .get[A](ds.identity(id))
      .fold[IO[(FetchEnv, A)]](
        ds.fetchOne(id).flatMap { (res: Option[A]) =>
          val endRound = System.nanoTime()
          res.fold[IO[(FetchEnv, A)]] {
            // could not get result from datasource
            IO.raiseError(NotFound(one))
          } { result =>
            // found result (and update cache)
            val newCache = env.cache.update(ds.identity(id), result)
            val round    = Round(env.cache, one, result, startRound, endRound)
            IO(env.evolve(round, newCache) -> result)
          }
        }
      ) { cached =>
        // get result from cache
        IO(env -> cached)
      }
  }

  private[this] def processMany[M[_], A](
      many: FetchMany[Any, Any],
      env: FetchEnv
  )(
      implicit ev: List[Any] =:= A
  ): IO[(FetchEnv, A)] = {
    val FetchMany(ids, ds) = many
    val startRound         = System.nanoTime()
    val cache              = env.cache

    def fetchIds(ids: NonEmptyList[Any], cachedResults: Map[Any, Any]): IO[Map[Any, Any]] =
      ds.fetchMany(ids).map(_ ++ cachedResults)

    def getResultList(resMap: Map[Any, Any]): IO[(FetchEnv, List[Any])] =
      ids
        .traverse(id => resMap.get(id).orElse(cache.getWithDS(ds)(id)).toValidNel(id))
        .fold[IO[(FetchEnv, List[Any])]](
          missingIds => IO.raiseError(MissingIdentities(Map(ds.name -> missingIds.toList))),
          results => {
            val endRound = System.nanoTime()
            val newCache = cache.cacheResults(resMap, ds)
            val round    = Round(cache, many, results, startRound, endRound)
            IO(env.evolve(round, newCache) -> results.toList)
          }
        )

    ids
      .reduceMap { id =>
        cache
          .getWithDS(ds)(id)
          .fold[IorNel[(Any, Any), NonEmptyList[Any]]](Ior.right(NonEmptyList(id, Nil)))(res =>
            Ior.left(NonEmptyList(id -> res, Nil)))
      }
      .leftMap(_.toList.toMap)
      .fold[IO[(FetchEnv, List[Any])]](
        resultMap => IO(env -> ids.map(resultMap).toList),
        uncachedIds => fetchIds(uncachedIds, Map.empty).flatMap(getResultList),
        (partResults, uncachedIds) => fetchIds(uncachedIds, partResults).flatMap(getResultList)
      )
      .map { case (env, l) => (env, ev(l)) } // A =:= List[Any]
  }

  private[this] def processConcurrent[M[_]](
      concurrent: Concurrent,
      env: FetchEnv
  ): IO[(FetchEnv, InMemoryCache)] = {

    val startRound = System.nanoTime()
    val cache      = env.cache

    type AnyQuery  = FetchQuery[Any, Any]
    type AnyResult = Map[Any, Any]

    def executeQueries(queries: NonEmptyList[FetchQuery[Any, Any]]): IO[
      (Long, NonEmptyList[(AnyQuery, AnyResult)])] =
      queries.traverse(runFetchQueryAsMap).flatMap { results =>
        val endRound = System.nanoTime()
        IO.fromEither(
          errorOrAllFound(queries, results)
          .map(zipped => (endRound, zipped.widen[(AnyQuery, AnyResult)])))
      }

    def runFetchQueryAsMap[I, A](op: FetchQuery[I, A]): IO[Map[I, A]] =
      op match {
        case FetchOne(a, ds) =>
          ds.fetchOne(a).map(_.fold(Map.empty[I, A])(r => Map(a -> r)))
        case FetchMany(as, ds) =>
          ds.fetchMany(as)
      }

    // return a MissingIdentities error or a NonEmptyList with all
    // the query - result pairs
    def errorOrAllFound(
        queries: NonEmptyList[AnyQuery],
        results: NonEmptyList[AnyResult]
    ): Either[MissingIdentities, NonEmptyList[(AnyQuery, AnyResult)]] = {
      val queriesAndResults = NonEmptyList.fromListUnsafe(queries.toList zip results.toList)

      val missingIdentitiesOrOK: Validated[Map[DataSourceName, List[Any]], Unit] =
        queriesAndResults.traverse_ {
          case (FetchOne(id, ds), resultMap) =>
            Either.cond(resultMap.size == 1, (), Map(ds.name -> List(id))).toValidated
          case (FetchMany(as, ds), resultMap) =>
            Either
              .cond(
                as.toList.size == resultMap.size,
                (),
                Map(ds.name -> as.toList.filter(id => resultMap.get(id).isEmpty)))
              .toValidated
          case _ =>
            Map.empty[DataSourceName, List[Any]].invalid
        }

      missingIdentitiesOrOK
        .as(queriesAndResults)
        .leftMap(missingIds => MissingIdentities(missingIds))
        .toEither
    }

    def executeQueriesAndEndRound(
        queries: NonEmptyList[AnyQuery],
        cachedResults: Map[DataSourceIdentity, Any]
    ): IO[(FetchEnv, InMemoryCache)] =
      executeQueries(queries).map {
        case (endRound, queriesAndResults) =>
          val queries = queriesAndResults.map(_._1)
          val results = queriesAndResults.map(_._2)
          val round   = Round(cache, Concurrent(queries), results, startRound, endRound)

          // since user-provided caches may discard elements, we use an in-memory
          // cache to gather these intermediate results that will be used for
          // concurrent optimizations.
          val (newCache, inmcache) = queriesAndResults.foldLeft((cache, InMemoryCache.empty)) {
            case ((userCache, internCache), (req, resultMap)) =>
              val anyMap = resultMap.asInstanceOf[AnyResult]
              val anyDS  = req.dataSource.castDS[Any, Any]
              (
                userCache.cacheResults(anyMap, anyDS),
                internCache.cacheResults(anyMap, anyDS).asInstanceOf[InMemoryCache])
          }

          (env.evolve(round, newCache), inmcache |+| InMemoryCache(cachedResults))
      }

    queriesIorCachedResults(concurrent.queries, cache)
      .map(_.toList.toMap)
      .fold(
        queries => executeQueriesAndEndRound(queries, Map.empty),
        resMap => IO((env, InMemoryCache(resMap))),
        (queries, resMap) => executeQueriesAndEndRound(queries, resMap)
      )
  }

  private[this] def queriesIorCachedResults[I, A](
      queries: NonEmptyList[FetchQuery[I, A]],
      cache: DataSourceCache
  ): IorNel[FetchQuery[I, A], NonEmptyList[(DataSourceIdentity, A)]] = {
    def idOrResult[I, A](id: I, ds: DataSource[I, A]): Either[I, (DataSourceIdentity, A)] = {
      val identity = ds.identity(id)
      cache.get[A](identity).map(identity -> _).toRight(id)
    }

    queries.reduceMap[IorNel[FetchQuery[I, A], NonEmptyList[(DataSourceIdentity, A)]]] { query =>
      val ds = query.dataSource
      query.identities
        .reduceMap(id =>
          idOrResult(id, ds).bimap(NonEmptyList.one, NonEmptyList.one).toIor)
        .leftMap {
          case NonEmptyList(id, Nil) => NonEmptyList.one(FetchOne(id, ds))
          case ids                   => NonEmptyList.one(FetchMany(ids, ds))
        }
    }
  }

}
