package controllers

import play.api._
import libs.concurrent.Promise
import libs.EventSource
import libs.iteratee.Enumerator
import libs.json.{Json, JsValue}
import libs.ws.WS
import play.api.mvc._
import concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import play.api.Play.current

import play.api.libs.concurrent.Execution.Implicits._

object Application extends Controller {

  case class Job(id: String, color: String, building: Boolean, duration: Int, estimated: Int)

  val baseUrl = Play.configuration.getString("jenkins.url").getOrElse("http://172.17.104.69:8080")
  val refreshInterval = Play.configuration.getInt("jenkins.refresh").getOrElse(10)

  val jobWriter = Json.writes[Job]

  def indexAll() = Action {
    Async {
      WS.url(s"$baseUrl/api/json").get().map { r =>
        val names: Seq[String] = (r.json \ "views").as[Seq[JsValue]].map { view =>
          (view \ "name").as[String]
        }
        Ok(views.html.all(names))
      }
    }
  }
  
  def index(id: String) = Action {
    Ok(views.html.index(id))
  }

  def view(id: String) = Action {
    Async {
      WS.url(s"$baseUrl/view/$id/api/json").get().map { response =>
        (response.json \ "jobs").as[Seq[JsValue]].map { jsonJob =>
          createEnumerator((jsonJob \ "name").as[String])
        }.reduceLeft((enumA, enumB) => enumA.interleave(enumB))
      }.map(enumerator => Ok.feed(enumerator.through(EventSource())).as("text/event-stream"))
    }
  }

  def createEnumerator(id: String) = {
    Enumerator.generateM[JsValue] {
      Promise.timeout(Some(""), Duration(refreshInterval, TimeUnit.SECONDS)).flatMap { some =>
        WS.url(s"$baseUrl/job/$id/api/json").get().flatMap { jobRes =>
          val color = (jobRes.json \ "color").as[String] match {
            case "blue" => "passed"
            case "red" => "failed"
            case "yellow" => "warning"
            case c:String if (c.contains("anime")) => "anime"
            case _ => "unknownstatus"
          }
          val url = (jobRes.json \ "lastBuild" \ "url").as[String]
          WS.url(s"$url/api/json").get().map { lastJobRes =>
            val building = (lastJobRes.json \ "building").as[Boolean]
            val duration = (lastJobRes.json \ "duration").as[Int]
            val estimated = (lastJobRes.json \ "estimatedDuration").as[Int]
            Some(jobWriter.writes(Job(id, color, building, duration, estimated)))
          }
        }
      }
    }
  }
}