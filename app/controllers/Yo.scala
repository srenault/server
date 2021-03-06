package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import models._
import services._
import utils.Http



object Yo extends Controller {

  val yoToken = Play.configuration.getString("yo.token").getOrElse("")

  def radio(token: String, radioName: String, yoAccount: String) = Action.async {
    if (yoToken != token) {
      Future.successful(BadRequest("Invalid token"))
    } else {
      val res = for {
        users <- User.find(yoAccount).flatMap( users => Future.sequence(users.map(User.refreshAccessToken(_))) )
        radio <- Radios.getRadio(radioName)
        track <- radio.currentTrack
        trackId <- SpotifySearch.search(track)
      } yield (users, trackId, track)

      res.flatMap {
        case (Nil, Some(trackId), track) =>
          Logger.debug(s"Yo account $yoAccount not found")
          SpotifyWS.trackInfos(trackId).flatMap { jsTrackInfos =>
            val coverUrl = ((jsTrackInfos \ "album" \ "images").as[JsArray].value(0) \ "url").as[String]
            val titleUrlParam = Http.encodeUrlParams(
              Map(
                "title" -> (track.titre + " - " + track.interpreteMorceau.getOrElse(""))
              )
            )
            val coverWithName = "https://radyo.herokuapp.com/spoimage/" + coverUrl.split("/").last + "?" + titleUrlParam
            YoWS.yoForRadio(yoAccount, radioName, coverWithName).map{ _ =>
              Ok(s"Yo: user not found")
            }
          }

        case (users, Some(trackId), track) =>
          SpotifyWS.trackInfos(trackId).flatMap { jsTrackInfos =>

            val coverUrl = ((jsTrackInfos \ "album" \ "images").as[JsArray].value(0) \ "url").as[String]
            val titleUrlParam =
              Http.encodeUrlParams(
                Map(
                  "title" -> (track.titre + " - " + track.interpreteMorceau.getOrElse(""))
                )
              )
            val coverWithName = "https://radyo.herokuapp.com/spoimage/" + coverUrl.split("/").last + "?" + titleUrlParam

            Logger.debug(s"found trackId $trackId for users $users on radio $radioName: image url = $coverWithName")
            Future.sequence(
              users.map { user =>
                (SpotifyWS.addToPlayList(user, trackId)
                  zip
                 Future.sequence(user.yoAccounts.map(YoWS.yoForRadio(_, radioName, coverWithName)))
                )
              }
            )
            .map { _ => Ok("Yo") }

          }
        case (users, None, track) =>

            val titleUrlParam =
              Http.encodeUrlParams(
                Map(
                  "title" -> (track.titre + " - " + track.interpreteMorceau.getOrElse(""))
                )
              )
            val songNotFound = "https://radyo.herokuapp.com/songNotFound?" + titleUrlParam

            Future.sequence(
              users.map { user =>
                 Future.sequence(user.yoAccounts.map(YoWS.yoForRadio(_, radioName, songNotFound)))
              }
            )
            .map { _ => Ok("Yo : can't find Track") }

        case (_, None, track) =>
          val titleUrlParam =
            Http.encodeUrlParams(
              Map(
                "title" -> (track.titre + " - " + track.interpreteMorceau.getOrElse(""))
              )
            )
          val songNotFound = "https://radyo.herokuapp.com/songNotFound?" + titleUrlParam
          YoWS.yoForRadio(yoAccount, radioName, songNotFound).map{_ =>
            Ok("Yo: can't find track nor user")
          }

      }
    }
  }

  def redirectImage(id: String, title: String) = Action {
    Ok(views.html.spotify_image(id, title))
  }

  def songNotFound(title: String) = Action {
    Ok(views.html.song_not_found(title))
  }

}
