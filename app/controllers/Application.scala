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

  case class Job(id: String, color: String, building: Boolean, duration: Int, estimated: Int, author: String, email: String, gravatar: String)

  val baseUrl = Play.configuration.getString("jenkins.url").getOrElse("https://ci.jenkins-ci.org")
  val refreshInterval = Play.configuration.getInt("jenkins.refresh").getOrElse(10)

  val jobWriter = Json.writes[Job]

  def indexAll(url: Option[String]) = Action { request =>
    Async {
      val jenkinsUrl = url.getOrElse(baseUrl)
      WS.url(s"$jenkinsUrl/api/json").withTimeout(5000).get().map { r =>
        val names: Seq[String] = (r.json \ "views").as[Seq[JsValue]].map(_.\("name").as[String])
        Ok(views.html.all(names, url, Some(refreshInterval)))
      }
    }
  }
  
  def index(viewId: String, url: Option[String], refresh: Option[Int]) = Action {
    Ok(views.html.index(viewId, url, refresh))
  }

  def view(viewId: String, url: Option[String], refresh: Option[Int]) = Action { request =>
    Async {
      val jenkinsRefresh = refresh.getOrElse(refreshInterval)
      val jenkinsUrl = url.getOrElse(baseUrl)
      WS.url(s"$jenkinsUrl/view/$viewId/api/json").withTimeout(5000).get().map { response =>
        (response.json \ "jobs").as[Seq[JsValue]].map { jsonJob =>
          createEnumerator((jsonJob \ "name").as[String], jenkinsRefresh, jenkinsUrl)
        }.reduceLeft((enumA, enumB) => enumA.interleave(enumB))
      }.map(enumerator => Ok.feed(enumerator.through(EventSource())).as("text/event-stream"))
    }
  }

  def createEnumerator(jobId: String, refresh: Int, jenkinsUrl: String) = {
    Enumerator.generateM[JsValue] {
      Promise.timeout(Some(""), Duration(refresh, TimeUnit.SECONDS)).flatMap { some =>
        WS.url(s"$jenkinsUrl/job/$jobId/api/json").withTimeout(5000).get().flatMap { jobRes =>
          val color = (jobRes.json \ "color").as[String] match {
            case "blue" => "passed"
            case "red" => "failed"
            case "yellow" => "warning"
            case c:String if (c.contains("anime")) => "anime"
            case _ => "unknownstatus"
          }
          val url = (jobRes.json \ "lastBuild" \ "url").as[String]
          WS.url(s"$url/api/json").withTimeout(5000).get().map { lastJobRes =>
            val json = lastJobRes.json
            val author = (json \ "changeSet" \ "items").as[Seq[JsValue]].headOption.map { value =>
              ((value \ "author" \ "fullName").as[String], (value \ "author" \ "absoluteUrl").as[String])
            }.getOrElse(("unknown", "unknown@test.com"))
            Future { populateCache(author._1, author._2) }
            Some(jobWriter.writes( Job(jobId, color,
                (json \ "building").as[Boolean],
                (json \ "duration").as[Int],
                (json \ "estimatedDuration").as[Int],
                author._1,
                Cache.getAs[String](emailKey(author._1)).getOrElse("unknown@test.com"),
                Cache.getAs[String](gravatarKey(author._1)).getOrElse("http://www.gravatar.com/avatar/xxxxxx?s=40&d=identicon"))
            ))
          }
        }
      }
    }
  }

  def emailKey(user: String) = s"$user-emailaddress"
  def gravatarKey(user: String) = s"$user-gravatarurl"

  def populateCache(user: String, url: String) = {
    if (Cache.get(emailKey(user)).isEmpty) {
      WS.url(s"$url/api/json").withTimeout(5000).get().map { authorRes =>
        val email = (authorRes.json \ "property").as[Seq[JsValue]].filter(_.\("address").asOpt[String].isDefined).headOption.map(_.\("address").as[String]).getOrElse("")
        val crypt = Codecs.md5(email.getBytes)
        val urlGravatar = s"http://www.gravatar.com/avatar/$crypt?s=40&d=identicon"
        if (Cache.get(emailKey(user)).isEmpty) {
          Cache.set(emailKey(user), email, 86400)
          Cache.set(gravatarKey(user), urlGravatar, 86400)
        }
      }
    }
  }
}