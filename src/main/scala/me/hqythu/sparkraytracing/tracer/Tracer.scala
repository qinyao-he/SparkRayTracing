/**
  * Created by hqythu on 7/17/16.
  */

package me.hqythu.sparkraytracing.tracer

import java.awt.image.BufferedImage

import me.hqythu.sparkraytracing.objects.Intersects
import me.hqythu.sparkraytracing.utils.{Color, Image, Vector3}
import org.apache.spark.{SparkConf, SparkContext}


class Tracer(camera: Camera, scene: Scene) extends Serializable {

  val width = camera.width
  val height = camera.height
  val traceDepth = 10

  def raytrace(ray: Ray, depth: Integer): Color = {
    if (depth >= traceDepth) {
      new Color()
    } else {
      val inter = scene.find_nearest_object(ray)

      if (!inter.intersects) {
        return new Color()
      }

      val obj = inter.object_ptr
      val material = obj.material
      val objColor = obj.getColor(inter)

      val color_bg = scene.backgroundColor * objColor * material.diff

      val color_diff = {
        var color = new Color()
        for (light <- scene.lights) {
          val toLight = new Ray(inter.position, (light.position - inter.position) % ())
          val inter_1 = scene.find_nearest_object(toLight)
          val dot = toLight.direction $ inter.normal
          if (!inter_1.intersects && dot > 0) {
            if (material.diff > 0) {
              color = color + light.color * objColor * material.diff * dot
            }
            if (material.spec > 0) {
              color = color + light.color * math.pow(dot, material.specn) * material.spec
            }
          }
        }
        color
      }

      val color_refl = if (material.refl > 0) {
        raytrace(ray.reflect(inter.normal, inter.position), depth + 1) * objColor * material.refl
      } else {
        new Color()
      }

      val color_refr = if (material.refr > 0) {
        raytrace(ray.refract(inter.normal, inter.position, material.index), depth + 1) * objColor * material.refr
      } else {
        new Color()
      }

      (color_bg + color_diff + color_refl + color_refr).confine
    }
  }

  def runspark(): BufferedImage = {
    val conf = new SparkConf()
      .setAppName("raytracer")
      .setMaster("local[*]")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", "me.hqythu.sparkraytracing.utils.MyRegisterKryo")

    val sc = SparkContext.getOrCreate(conf)

//    val pointList = sc.parallelize((0 until width * height).map((i: Int) => (i / height, i % height)))
    val pointList = sc.parallelize(0 until width).cartesian(sc.parallelize(0 until height))

    val colors = pointList
      .map((p: (Int, Int)) => (p, camera.emit(p._1, p._2)))
      //      .map(r => (r._1, raytrace(r._2, 0).toRGB))
      .map(r => (r._1, raytrace(r._2, 0)))
      .collect()

    val image = new Image(width, height)

    for (color <- colors) {
      image.setColor(color._1._1, color._1._2, color._2)
    }

    //    for (color <- colors) {
    //      image.setColor(color._1._1, color._1._2, color._2._1, color._2._2, color._2._3)
    //    }

    image.toBufferedImage
  }

  def run(): BufferedImage = {
    val image = new Image(width, height)

    val start = System.currentTimeMillis()

    for (i <- 0 until width) {
      for (j <- 0 until height) {
        val color = raytrace(camera.emit(i, j), 0)
        image.setColor(i, j, color)
      }
    }

    val end = System.currentTimeMillis()

    System.out.println((end - start) / 1000.0)

    image.toBufferedImage
  }

}
