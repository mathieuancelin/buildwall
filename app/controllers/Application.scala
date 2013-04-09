package controllers

import play.api._
import cache.Cache
import libs.concurrent.Promise
import libs.{Codecs, Crypto, EventSource}
import libs.iteratee.Enumerator
import libs.json.{Json, JsValue}
import libs.ws.WS
import play.api.mvc._
import concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import play.api.Play.current

import play.api.libs.concurrent.Execution.Implicits._
import concurrent.Future

object Application extends Controller {

  case class Job(id: String, color: String, building: Boolean, duration: Int, estimated: Int, author: String, email: String)

  val baseUrl = Play.configuration.getString("jenkins.url").getOrElse("http://172.17.104.69:8080")
  val refreshInterval = Play.configuration.getInt("jenkins.refresh").getOrElse(10)

  val jobWriter = Json.writes[Job]

  def indexAll() = Action {
    Async {
      WS.url(s"$baseUrl/api/json").get().map { r =>
        val names: Seq[String] = (r.json \ "views").as[Seq[JsValue]].map(_.\("name").as[String])
        Ok(views.html.all(names))
      }
    }
  }
  
  def index(viewId: String) = Action {
    Ok(views.html.index(viewId))
  }

  def view(viewId: String) = Action { request =>
    Async {
      val refresh = request.queryString.get("refresh").flatMap(_.headOption).map(_.toInt).getOrElse(refreshInterval)
      WS.url(s"$baseUrl/view/$viewId/api/json").get().map { response =>
        (response.json \ "jobs").as[Seq[JsValue]].map { jsonJob =>
          createEnumerator((jsonJob \ "name").as[String], refresh)
        }.reduceLeft((enumA, enumB) => enumA.interleave(enumB))
      }.map(enumerator => Ok.feed(enumerator.through(EventSource())).as("text/event-stream"))
    }
  }

  def createEnumerator(jobId: String, refresh: Int) = {
    Enumerator.generateM[JsValue] {
      Promise.timeout(Some(""), Duration(refresh, TimeUnit.SECONDS)).flatMap { some =>
        WS.url(s"$baseUrl/job/$jobId/api/json").get().flatMap { jobRes =>
          val color = (jobRes.json \ "color").as[String] match {
            case "blue" => "passed"
            case "red" => "failed"
            case "yellow" => "warning"
            case c:String if (c.contains("anime")) => "anime"
            case _ => "unknownstatus"
          }
          val url = (jobRes.json \ "lastBuild" \ "url").as[String]
          WS.url(s"$url/api/json").get().map { lastJobRes =>
            val json = lastJobRes.json
            val author = (json \ "changeSet" \ "items").as[Seq[JsValue]].headOption.map { value =>
              (value \ "author" \ "fullName").as[String]
            }.getOrElse("")
            val email = author.trim.toLowerCase().split(" ").mkString(".").concat("@test.com")
            val crypt = Codecs.md5(email.getBytes)
            val url = s"http://www.gravatar.com/avatar/$crypt?s=40&d=identicon"
            Some(jobWriter.writes( Job(jobId, color,
                (json \ "building").as[Boolean],
                (json \ "duration").as[Int],
                (json \ "estimatedDuration").as[Int], author, url)
            ))
          }
        }
      }
    }
  }
}


/**
(json \ "changeSet" \ "items").as[Seq[JsValue]].headOption.map { value =>
  (value \ "author" \ "absoluteUrl").as[String]
}.map { absoluteUrl =>
  WS.url(s"$absoluteUrl/api/json").get().map({ authorRes =>
    val email = (authorRes.json \ "property").as[Seq[JsValue]].filter(_.\("address").asOpt[String].isDefined).headOption.map(_.\("address").as[String]).getOrElse("")
    Some(jobWriter.writes( Job(jobId, color,
      (json \ "building").as[Boolean],
      (json \ "duration").as[Int],
      (json \ "estimatedDuration").as[Int], author, email)
    ))
  })
}.getOrElse(
  Future(Some(jobWriter.writes( Job(jobId, color,
    (json \ "building").as[Boolean],
    (json \ "duration").as[Int],
    (json \ "estimatedDuration").as[Int], author, "")
  )))
)
**/