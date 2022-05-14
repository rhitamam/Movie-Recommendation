package shared

import breeze.linalg._
import breeze.numerics._
import scala.io.Source
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.SparkContext

package object predictions
{
  // ------------------------ For template
  case class Rating(user: Int, item: Int, rating: Double)

  def timingInMs(f : ()=>Double ) : (Double, Double) = {
    val start = System.nanoTime() 
    val output = f()
    val end = System.nanoTime()
    return (output, (end-start)/1000000.0)
  }

  def toInt(s: String): Option[Int] = {
    try {
      Some(s.toInt)
    } catch {
      case e: Exception => None
    }
  }

  def mean(s :Seq[Double]): Double =  if (s.size > 0) s.reduce(_+_) / s.length else 0.0

  def std(s :Seq[Double]): Double = {
    if (s.size == 0) 0.0
    else { 
      val m = mean(s)
      scala.math.sqrt(s.map(x => scala.math.pow(m-x, 2)).sum / s.length.toDouble) 
    }
  }


  def load(path : String, sep : String, nbUsers : Int, nbMovies : Int) : CSCMatrix[Double] = {
    val file = Source.fromFile(path)
    val builder = new CSCMatrix.Builder[Double](rows=nbUsers, cols=nbMovies) 
    for (line <- file.getLines) {
      val cols = line.split(sep).map(_.trim)
      toInt(cols(0)) match {
        case Some(_) => builder.add(cols(0).toInt-1, cols(1).toInt-1, cols(2).toDouble)
        case None => None
      }
    }
    file.close
    builder.result()
  }

  def loadSpark(sc : org.apache.spark.SparkContext,  path : String, sep : String, nbUsers : Int, nbMovies : Int) : CSCMatrix[Double] = {
    val file = sc.textFile(path)
    val ratings = file
      .map(l => {
        val cols = l.split(sep).map(_.trim)
        toInt(cols(0)) match {
          case Some(_) => Some(((cols(0).toInt-1, cols(1).toInt-1), cols(2).toDouble))
          case None => None
        }
      })
      .filter({ case Some(_) => true
                 case None => false })
      .map({ case Some(x) => x
             case None => ((-1, -1), -1) }).collect()

    val builder = new CSCMatrix.Builder[Double](rows=nbUsers, cols=nbMovies)
    for ((k,v) <- ratings) {
      v match {
        case d: Double => {
          val u = k._1
          val i = k._2
          builder.add(u, i, d)
        }
      }
    }
    return builder.result
  }

  def partitionUsers (nbUsers : Int, nbPartitions : Int, replication : Int) : Seq[Set[Int]] = {
    val r = new scala.util.Random(1337)
    val bins : Map[Int, collection.mutable.ListBuffer[Int]] = (0 to (nbPartitions-1))
       .map(p => (p -> collection.mutable.ListBuffer[Int]())).toMap
    (0 to (nbUsers-1)).foreach(u => {
      val assignedBins = r.shuffle(0 to (nbPartitions-1)).take(replication)
      for (b <- assignedBins) {
        bins(b) += u
      }
    })
    bins.values.toSeq.map(_.toSet)
  }

  // Useful functions

  def maeCSC(predictor : (Int,Int) => Double, test : CSCMatrix[Double]) : Double = {
    var s = 0.0
    for ((k,v) <- test.activeIterator) {
      val u = k._1
      val i = k._2
      s = s + (predictor(u+1,i+1) - v).abs
    }
    s/test.activeSize
  }

  def simkNN(u : Int, v : Int, simMatrix : CSCMatrix[Double]) : Double = {
    simMatrix(u-1,v-1)
  }

  // Part 3: Optimizing with Breeze, a Linear Algebra Library

  def vectorOnes(n : Int) : CSCMatrix[Double] = {
    val builder = new CSCMatrix.Builder[Double](rows=n, cols=1)
    for (k <- 0 to n-1) {
      builder.add(k,0,1)
    }
    builder.result()
  }

  def avgRating(ratings : CSCMatrix[Double]) : Double = {
    sum(ratings)/ratings.activeSize
  }

  def avgRatingUserMatrix(ratings : CSCMatrix[Double], users : Int, movies : Int) : CSCMatrix[Double] = {
    val counts = Array.fill(users)(0)
    val builder = new CSCMatrix.Builder[Double](rows=users, cols=1)
    for ((k,v) <- ratings.activeIterator) {
      val u = k._1
      builder.add(u, 0, v)
      counts(u) = counts(u) + 1
    }
    val avgMat = builder.result()
    for (u <- 0 to users-1){
      if (counts(u) != 0) {avgMat(u,0) = avgMat(u,0)/counts(u)} else {avgMat(u,0) = -2}
    }
    avgMat
  }

  def scale(x : Double, y : Double) : Double = 
    if (x > y) 5-y else if (x < y) y-1 else 1

  def normalizedDev(r_u_i : Double, user : Int, avgMatrix : CSCMatrix[Double]) : Double = {
    val r_u = avgMatrix(user,0)
    (r_u_i - r_u)/scale(r_u_i, r_u)
  }

  def normalizedDevMatrix(ratings : CSCMatrix[Double], avgMatrix : CSCMatrix[Double], users : Int, movies : Int) : CSCMatrix[Double] = {
    val builder = new CSCMatrix.Builder[Double](rows=users, cols=movies)
    for ((k,v) <- ratings.activeIterator) {
      val u = k._1
      val i = k._2
      builder.add(u, i, normalizedDev(v,u,avgMatrix))
    }
    builder.result()
  }

  def processedMatrix(normalizedMatrix : CSCMatrix[Double], users : Int, movies : Int) : CSCMatrix[Double] = {
    var sumSquareMatrix = (normalizedMatrix *:* normalizedMatrix) * vectorOnes(movies)
    val builder = new CSCMatrix.Builder[Double](rows=users, cols=movies)
    for ((k,v) <- normalizedMatrix.activeIterator) {
      val row = k._1
      val col = k._2
      builder.add(row,col,v/scala.math.sqrt(sumSquareMatrix(row,0)))
    }
    builder.result()
  }

  def simOpt(k : Int, preProcessedMatrix : CSCMatrix[Double], users : Int) : CSCMatrix[Double] = {
    var simMat = preProcessedMatrix * preProcessedMatrix.t
    val builder = new CSCMatrix.Builder[Double](rows=users, cols=users)
    for (u <- 0 to users-1) {
      for (v <- argtopk(simMat(u,0 to users-1).t,k+1)) {
        if (u != v) builder.add(u, v, simMat(u,v))
      }
    }
    builder.result()
  }

  def kNNRating(user : Int, item : Int, normalizedMatrix : CSCMatrix[Double], simMatrix : CSCMatrix[Double],users : Int, ratings : CSCMatrix[Double]) : Double = {
    var num = 0.0
    var denom = 0.0
    for (v <- 0 to users-1) {
      if (ratings(v,item) != 0) {
        num = num + simMatrix(user,v)*normalizedMatrix(v,item)
        denom = denom + simMatrix(user,v).abs
      }
    }
    if (denom != 0) num/denom else -2
  }

  def predictionKNN(k : Int, ratings : CSCMatrix[Double], users : Int, movies : Int) : ((Int,Int) => Double) = {
    val avg = avgRating(ratings)
    val avgMatrix = avgRatingUserMatrix(ratings,users,movies)
    val normalizedMatrix = normalizedDevMatrix(ratings,avgMatrix,users,movies)
    val preProcessedMatrix = processedMatrix(normalizedMatrix,users,movies)
    val simMatrix = simOpt(k,preProcessedMatrix,users)
    var r_u : Double = 0.0
    ((u,i) => {
    var r_i = kNNRating(u-1,i-1,normalizedMatrix,simMatrix,users,ratings)
    r_u = avgMatrix(u-1,0)
    if (r_u == -2) {avg} else {
      if (r_i != -2) {r_u + r_i * scale(r_u + r_i, r_u)} 
      else {r_u}}
    })
  }

  // Part 4: Parallel k-NN Computations with Replicated Ratings

  def topk(u : Int, br : org.apache.spark.broadcast.Broadcast[CSCMatrix[Double]], k : Int, movies : Int) : (Int,Seq[(Int,Double)]) = {
    val r = br.value
    val sim_u = r * r(u,0 to movies-1).t
    (u,argtopk(sim_u,k+1).filter(_ != u).map(x => (x, sim_u(x))))
  }

  def simkNNparallelized(u : Int, v : Int, k : Int, preProcessedMatrix : CSCMatrix[Double], movies : Int) : Double = {
    val sim_u = preProcessedMatrix * preProcessedMatrix(u-1,0 to movies-1).t
    if (argtopk(sim_u,k+1).filter(_ != u-1) contains v-1) {
      sim_u(v-1)
    } else 0
  }

  def parallelizedKNN(ratings : CSCMatrix[Double], k: Int, sc : SparkContext, users : Int, movies : Int): ((Int,Int) => Double) = {
    val avg = avgRating(ratings)
    val avgMatrix = avgRatingUserMatrix(ratings,users,movies)
    val normalizedMatrix = normalizedDevMatrix(ratings,avgMatrix,users,movies)
    val preProcessedMatrix = processedMatrix(normalizedMatrix,users,movies)
    val br = sc.broadcast(preProcessedMatrix)
    val topku = sc.parallelize(0 to users-1).map(u=> topk(u,br,k,movies)).collect()
    val builder = new CSCMatrix.Builder[Double](rows= users, cols= users)
    for (x <- topku) {
      for (y <- x._2) {
        builder.add(x._1,y._1,y._2)
      }
    }
    val mat = builder.result()
    var r_u : Double = 0.0
    ((u,i) => {
    var r_i = kNNRating(u-1,i-1,normalizedMatrix,mat,users,ratings)
    if (u-1 >= 0 && u-1 < users) {r_u = avgMatrix(u-1,0)} else avg
    r_u + r_i * scale(r_u + r_i, r_u)
    })
  }

  // Part 5: Distributed Approximate k-NN

  def createPartMat(list_user : Set[Int], preProcessedMatrix : CSCMatrix[Double], users : Int, movies : Int) : CSCMatrix[Double] = {
    val builder = new CSCMatrix.Builder[Double](rows = users, cols = movies)
    list_user.map(u=> (0 to movies-1).map(i=>builder.add(u,i, preProcessedMatrix(u,i))))
    builder.result()
  }

  def simPartition(k : Int, preProcessedMatrix : CSCMatrix[Double], list_user : Seq[Int], users : Int) : Seq[(Int,Seq[(Int,Double)])] = {
      var simMat = preProcessedMatrix * preProcessedMatrix.t
      list_user.map(u => (u,argtopk(simMat(u,0 to users-1).t,k+1).filter(_ != u).map(v=>(v,simMat(u,v)))))
  }

  def simApprox(preProcessedMatrix : CSCMatrix[Double], k: Int, sc : SparkContext, partitions : Seq[Set[Int]], users : Int, movies : Int): CSCMatrix[Double] = {
    val partitionRDD = sc.parallelize(partitions)
    val simPartitionned = partitionRDD.flatMap(p=> simPartition(k, createPartMat(p, preProcessedMatrix,users,movies), p.toSeq, users)).reduceByKey{case (x,y) => x.union(y).distinct}
    val builder = new CSCMatrix.Builder[Double](rows = users, cols = users)
    for (listu <- simPartitionned.collect()) {
      for (listv <- listu._2.sortBy(x => x._2)(Ordering.Double.reverse).take(k)) {
        builder.add(listu._1, listv._1, listv._2)
      }
    }
    val simMat = builder.result()
    simMat
  }

  def approximateKNN(ratings : CSCMatrix[Double], k: Int, sc : SparkContext, partitions : Seq[Set[Int]], users :Int, movies : Int): ((Int,Int) => Double) = {
    val avg = avgRating(ratings)
    val avgMatrix = avgRatingUserMatrix(ratings,users,movies)
    val normalizedMatrix = normalizedDevMatrix(ratings,avgMatrix,users,movies)
    val preProcessedMatrix = processedMatrix(normalizedMatrix,users,movies)
    val simMat = simApprox(preProcessedMatrix,k,sc,partitions,users,movies)
    var r_u : Double = 0.0
    ((u,i) => {
    var r_i = kNNRating(u-1,i-1,normalizedMatrix,simMat,users,ratings)
    if (u-1 >= 0 && u-1 < users) {r_u = avgMatrix(u-1,0)} else avg
    r_u + r_i * scale(r_u + r_i, r_u)
    })
  }

}


