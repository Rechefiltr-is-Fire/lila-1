package lila.round

import actorApi.SocketStatus
import strategygames.format.{ FEN, Forsyth }
import strategygames.{ Clock, Player => PlayerIndex, P1, P2, Pos, Situation }
import strategygames.variant.Variant
import play.api.libs.json._
import scala.math

import lila.common.ApiVersion
import lila.game.JsonView._
import lila.game.{ Event, Pov, Game, Player => GamePlayer }
import lila.pref.Pref
import lila.user.{ User, UserRepo }

final class JsonView(
    userRepo: UserRepo,
    userJsonView: lila.user.JsonView,
    gameJsonView: lila.game.JsonView,
    getSocketStatus: Game => Fu[SocketStatus],
    takebacker: Takebacker,
    moretimer: Moretimer,
    divider: lila.game.Divider,
    evalCache: lila.evalCache.EvalCacheApi,
    isOfferingRematch: Pov => Boolean
)(implicit ec: scala.concurrent.ExecutionContext) {

  import JsonView._

  private def checkCount(game: Game, playerIndex: PlayerIndex) =
    (game.variant == strategygames.chess.variant.ThreeCheck || game.variant == strategygames.chess.variant.FiveCheck) option game.history
      .checkCount(playerIndex)

  private def score(game: Game, playerIndex: PlayerIndex) =
    (game.variant == strategygames.togyzkumalak.variant.Togyzkumalak) option game.history
      .score(playerIndex)

  private def kingMoves(game: Game, playerIndex: PlayerIndex) =
    (game.variant.frisianVariant) option game.history.kingMoves(playerIndex)

  private def onlyDropsVariantForCurrentAction(pov: Pov): Boolean = {
    pov.game.variant.onlyDropsVariant || (pov.game.variant.key == "amazons" && pov.game.situation.destinations.size == 0)
  }

  private def coordSystemForVariant(prefCoordSystem: Int, gameVariant: Variant): Int =
    gameVariant match {
      case Variant.Draughts(v) => {
        if (v.invertNumericCoords && prefCoordSystem == 0) 2
        else if (!v.draughts64Variant && prefCoordSystem == 1) 0
        else prefCoordSystem
      }
      case _ => prefCoordSystem
    }

  private def commonPlayerJson(g: Game, p: GamePlayer, user: Option[User], withFlags: WithFlags): JsObject =
    Json
      //.obj("color" -> g.variant.playerNames(p.playerIndex), "playerIndex" -> p.playerIndex.name)
      .obj(
        //"color" -> p.playerIndex.classicName,
        "playerName"  -> g.variant.playerNames(p.playerIndex),
        "playerIndex" -> p.playerIndex.name,
        "playerColor" -> g.variant.playerColors(p.playerIndex)
      )
      .add("user" -> user.map { userJsonView.minimal(_, g.perfType) })
      .add("rating" -> p.rating)
      .add("ratingDiff" -> p.ratingDiff)
      .add("provisional" -> p.provisional)
      .add("offeringRematch" -> isOfferingRematch(Pov(g, p)))
      .add("offeringDraw" -> p.isOfferingDraw)
      .add("proposingTakeback" -> p.isProposingTakeback)
      .add("checks" -> checkCount(g, p.playerIndex))
      .add("score" -> score(g, p.playerIndex))
      .add("kingMoves" -> kingMoves(g, p.playerIndex))
      .add("berserk" -> p.berserk)
      .add("blurs" -> (withFlags.blurs ?? blurs(g, p)))

  def playerJson(
      pov: Pov,
      pref: Pref,
      apiVersion: ApiVersion,
      playerUser: Option[User],
      initialFen: Option[FEN],
      withFlags: WithFlags,
      nvui: Boolean
  ): Fu[JsObject] =
    getSocketStatus(pov.game) zip
      (pov.opponent.userId ?? userRepo.byId) zip
      takebacker.isAllowedIn(pov.game) zip
      moretimer.isAllowedIn(pov.game) map { case (((socket, opponentUser), takebackable), moretimeable) =>
        import pov._
        Json
          .obj(
            "game" -> gameJsonView(pov.game, initialFen),
            "player" -> {
              commonPlayerJson(pov.game, player, playerUser, withFlags) ++ Json.obj(
                "id"      -> playerId,
                "version" -> socket.version.value
              )
            }.add("onGame" -> (player.isAi || socket.onGame(player.playerIndex))),
            "opponent" -> {
              commonPlayerJson(pov.game, opponent, opponentUser, withFlags) ++ Json.obj(
                //"color" -> pov.game.variant.playerNames(opponent.playerIndex),
                //"color" -> opponent.playerIndex.classicName,
                "playerName"  -> pov.game.variant.playerNames(opponent.playerIndex),
                "playerIndex" -> opponent.playerIndex.name,
                "playerColor" -> pov.game.variant.playerColors(opponent.playerIndex),
                "ai"          -> opponent.aiLevel
              )
            }.add("isGone" -> (!opponent.isAi && socket.isGone(opponent.playerIndex)))
              .add("onGame" -> (opponent.isAi || socket.onGame(opponent.playerIndex))),
            "url" -> Json.obj(
              "socket" -> s"/play/$fullId/v$apiVersion",
              "round"  -> s"/$fullId"
            ),
            "captureLength" -> captureLength(pov),
            "pref" -> Json
              .obj(
                "animationDuration" -> animationMillis(pov, pref),
                "coords"            -> pref.coords,
                "coordSystem"       -> coordSystemForVariant(pref.coordSystem, pov.game.variant),
                "resizeHandle"      -> pref.resizeHandle,
                "replay"            -> pref.replay,
                "autoQueen" -> (if (pov.game.variant == strategygames.chess.variant.Antichess)
                                  Pref.AutoQueen.NEVER
                                else pref.autoQueen),
                "clockTenths" -> pref.clockTenths,
                "moveEvent"   -> pref.moveEvent,
                "mancalaMove" -> (pref.mancalaMove == Pref.MancalaMove.SINGLE_CLICK),
                "pieceSet" -> pref.pieceSet.map(p =>
                  Json.obj("name" -> p.name, "gameFamily" -> p.gameFamilyName)
                )
              )
              .add("is3d" -> pref.is3d)
              .add("clockBar" -> pref.clockBar)
              .add("clockSound" -> pref.clockSound)
              .add("confirmResign" -> (!nvui && pref.confirmResign == Pref.ConfirmResign.YES))
              .add("keyboardMove" -> (!nvui && pref.keyboardMove == Pref.KeyboardMove.YES))
              .add("rookCastle" -> (pref.rookCastle == Pref.RookCastle.YES))
              .add("blindfold" -> pref.isBlindfold)
              .add("highlight" -> pref.highlight)
              .add("destination" -> (pref.destination && !pref.isBlindfold))
              .add("playerTurnIndicator" -> pref.playerTurnIndicator)
              .add("enablePremove" -> pref.premove)
              .add("showCaptured" -> pref.captured)
              .add("submitMove" -> {
                import Pref.SubmitMove._
                pref.submitMove match {
                  case _ if pov.game.hasAi || nvui                            => false
                  case ALWAYS                                                 => true
                  case CORRESPONDENCE_UNLIMITED if pov.game.isCorrespondence  => true
                  case CORRESPONDENCE_ONLY if pov.game.hasCorrespondenceClock => true
                  case _                                                      => false
                }
              })
          )
          .add("clock" -> pov.game.clock.map(clockJson))
          .add("correspondence" -> pov.game.correspondenceClock)
          .add("takebackable" -> takebackable)
          .add("moretimeable" -> moretimeable)
          .add("crazyhouse" -> pov.game.board.pocketData)
          .add("onlyDropsVariant" -> onlyDropsVariantForCurrentAction(pov))
          .add("hasGameScore" -> pov.game.variant.hasGameScore)
          .add("possibleMoves" -> possibleMoves(pov, apiVersion))
          .add("possibleDrops" -> possibleDrops(pov))
          .add("possibleDropsByRole" -> possibleDropsByrole(pov))
          .add("expiration" -> pov.game.expirable.option {
            Json.obj(
              "idleMillis"   -> (nowMillis - pov.game.movedAt.getMillis),
              "millisToMove" -> pov.game.timeForFirstMove.millis
            )
          })
      }

  private def commonWatcherJson(g: Game, p: GamePlayer, user: Option[User], withFlags: WithFlags): JsObject =
    Json
      .obj(
        //"color" -> g.variant.playerNames(p.playerIndex),
        //"color" -> p.playerIndex.classicName,
        "playerName"   -> g.variant.playerNames(p.playerIndex),
        "playerIndex"  -> p.playerIndex.name,
        "playerColors" -> g.variant.playerColors(p.playerIndex),
        "name"         -> p.name
      )
      .add("user" -> user.map { userJsonView.minimal(_, g.perfType) })
      .add("ai" -> p.aiLevel)
      .add("rating" -> p.rating)
      .add("ratingDiff" -> p.ratingDiff)
      .add("provisional" -> p.provisional)
      .add("checks" -> checkCount(g, p.playerIndex))
      .add("score" -> score(g, p.playerIndex))
      .add("kingMoves" -> kingMoves(g, p.playerIndex))
      .add("berserk" -> p.berserk)
      .add("blurs" -> (withFlags.blurs ?? blurs(g, p)))

  def watcherJson(
      pov: Pov,
      pref: Pref,
      apiVersion: ApiVersion,
      me: Option[User],
      tv: Option[OnTv],
      initialFen: Option[FEN] = None,
      withFlags: WithFlags
  ) =
    getSocketStatus(pov.game) zip
      userRepo.pair(pov.player.userId, pov.opponent.userId) map { case (socket, (playerUser, opponentUser)) =>
        import pov._
        Json
          .obj(
            "game" -> gameJsonView(game, initialFen)
              .add("moveCentis" -> (withFlags.movetimes ?? game.moveTimes.map(_.map(_.centis))))
              .add("division" -> withFlags.division.option(divider(game, initialFen)))
              .add("opening" -> game.opening)
              .add("importedBy" -> game.pgnImport.flatMap(_.user)),
            "clock"          -> game.clock.map(clockJson),
            "correspondence" -> game.correspondenceClock,
            "player" -> {
              commonWatcherJson(game, player, playerUser, withFlags) ++ Json.obj(
                "version"   -> socket.version.value,
                "spectator" -> true,
                "id"        -> me.flatMap(game.player).map(_.id)
              )
            }.add("onGame" -> (player.isAi || socket.onGame(player.playerIndex))),
            "opponent" -> commonWatcherJson(game, opponent, opponentUser, withFlags).add(
              "onGame" -> (opponent.isAi || socket.onGame(opponent.playerIndex))
            ),
            "captureLength" -> captureLength(pov),
            //"orientation" -> pov.game.variant.playerNames(pov.playerIndex),
            "orientation" -> pov.playerIndex.name,
            "url" -> Json.obj(
              "socket" -> s"/watch/$gameId/${game.variant.playerNames(playerIndex)}/v$apiVersion",
              "round"  -> s"/$gameId/${game.variant.playerNames(playerIndex)}"
            ),
            "pref" -> Json
              .obj(
                "animationDuration" -> animationMillis(pov, pref),
                "coords"            -> pref.coords,
                "coordSystem"       -> coordSystemForVariant(pref.coordSystem, game.variant),
                "resizeHandle"      -> pref.resizeHandle,
                "replay"            -> pref.replay,
                "clockTenths"       -> pref.clockTenths,
                "mancalaMove"       -> (pref.mancalaMove == Pref.MancalaMove.SINGLE_CLICK),
                "pieceSet" -> pref.pieceSet.map(p =>
                  Json.obj("name" -> p.name, "gameFamily" -> p.gameFamilyName)
                )
              )
              .add("is3d" -> pref.is3d)
              .add("clockBar" -> pref.clockBar)
              .add("highlight" -> pref.highlight)
              .add("destination" -> (pref.destination && !pref.isBlindfold))
              .add("playerTurnIndicator" -> false)
              .add("rookCastle" -> (pref.rookCastle == Pref.RookCastle.YES))
              .add("showCaptured" -> pref.captured),
            "evalPut" -> JsBoolean(me.??(evalCache.shouldPut))
          )
          .add("evalPut" -> me.??(evalCache.shouldPut))
          .add("tv" -> tv.collect { case OnPlayStrategyTv(channel, flip) =>
            Json.obj("channel" -> channel, "flip" -> flip)
          })
          .add("userTv" -> tv.collect { case OnUserTv(userId) =>
            Json.obj("id" -> userId)
          })
          .add("onlyDropsVariant" -> onlyDropsVariantForCurrentAction(pov))
          .add("hasGameScore" -> pov.game.variant.hasGameScore)

      }

  def userAnalysisJson(
      pov: Pov,
      pref: Pref,
      initialFen: Option[FEN],
      orientation: PlayerIndex,
      owner: Boolean,
      me: Option[User],
      division: Option[strategygames.Division] = none
  ) = {
    import pov._
    val fen = Forsyth.>>(game.variant.gameLogic, game.chess)
    Json
      .obj(
        "game" -> Json
          .obj(
            "id"         -> gameId,
            "lib"        -> game.variant.gameLogic.id,
            "variant"    -> game.variant,
            "opening"    -> game.opening,
            "initialFen" -> (initialFen | Forsyth.initial(game.variant.gameLogic)),
            "fen"        -> fen,
            "turns"      -> game.turns,
            "player"     -> game.turnPlayerIndex.name,
            "status"     -> game.status,
            "gameFamily" -> game.variant.gameFamily.key
          )
          .add("division", division)
          .add("winner", game.winner.map(w => game.variant.playerNames(w.playerIndex))),
        "player" -> Json.obj(
          "id" -> owner.option(pov.playerId),
          //"color" -> game.variant.playerNames(playerIndex),
          //"color" -> playerIndex.classicName,
          "playerName"  -> game.variant.playerNames(playerIndex),
          "playerIndex" -> playerIndex.name,
          "playerColor" -> game.variant.playerColors(playerIndex)
        ),
        "opponent" -> Json.obj(
          //"color" -> game.variant.playerNames(opponent.playerIndex),
          //"color" -> playerIndex.classicName,
          "playerName"  -> game.variant.playerNames(opponent.playerIndex),
          "playerIndex" -> opponent.playerIndex.name,
          "playerColor" -> game.variant.playerColors(opponent.playerIndex),
          "ai"          -> opponent.aiLevel
        ),
        "orientation" -> orientation.name,
        "pref" -> Json
          .obj(
            "animationDuration" -> animationMillis(pov, pref),
            "coords"            -> pref.coords,
            "coordSystem"       -> coordSystemForVariant(pref.coordSystem, game.variant),
            "moveEvent"         -> pref.moveEvent,
            "mancalaMove"       -> (pref.mancalaMove == Pref.MancalaMove.SINGLE_CLICK),
            "pieceSet"          -> pref.pieceSet.map(p => Json.obj("name" -> p.name, "gameFamily" -> p.gameFamilyName))
          )
          .add("rookCastle" -> (pref.rookCastle == Pref.RookCastle.YES))
          .add("is3d" -> pref.is3d)
          .add("highlight" -> pref.highlight)
          .add("destination" -> (pref.destination && !pref.isBlindfold))
          .add("playerTurnIndicator" -> false),
        "path"         -> pov.game.turns,
        "userAnalysis" -> true
      )
      .add("evalPut" -> me.??(evalCache.shouldPut))
      .add("possibleDropsByRole" -> possibleDropsByrole(pov))
      .add("onlyDropsVariant" -> onlyDropsVariantForCurrentAction(pov))
      .add("hasGameScore" -> pov.game.variant.hasGameScore)
  }

  private def blurs(game: Game, player: lila.game.Player) =
    player.blurs.nonEmpty option {
      blursWriter.writes(player.blurs) +
        ("percent" -> JsNumber(game.playerBlurPercent(player.playerIndex)))
    }

  private def clockJson(clock: Clock): JsObject =
    clockWriter.writes(clock) + ("moretime" -> JsNumber(actorApi.round.Moretime.defaultDuration.toSeconds))

  private def possibleMoves(pov: Pov, apiVersion: ApiVersion): Option[JsValue] =
    (pov.game.situation, pov.game.variant) match {
      //TODO: The Draughts specific logic should be pushed into strategygames
      //and should be ready to go now validMoves handles this ghosts logic internally
      //see Situation.Draughts.destinations
      case (Situation.Chess(_), Variant.Chess(_)) =>
        (pov.game playableBy pov.player) option
          Event.PossibleMoves.json(pov.game.situation.destinations, apiVersion)
      case (Situation.Draughts(situation), Variant.Draughts(variant)) =>
        (pov.game playableBy pov.player) option {
          if (situation.ghosts > 0) {
            val move    = pov.game.pgnMoves(pov.game.pgnMoves.length - 1)
            val destPos = variant.boardSize.pos.posAt(move.substring(move.lastIndexOf('x') + 1))
            destPos match {
              case Some(dest) =>
                Event.PossibleMoves.json(
                  Map(Pos.Draughts(dest) -> situation.destinationsFrom(dest).map(Pos.Draughts)),
                  apiVersion
                )
              case _ =>
                Event.PossibleMoves.json(
                  situation.allDestinations.map { case (p, lp) =>
                    (Pos.Draughts(p), lp.map(Pos.Draughts))
                  },
                  apiVersion
                )
            }
          } else {
            Event.PossibleMoves.json(
              situation.allDestinations.map { case (p, lp) =>
                (Pos.Draughts(p), lp.map(Pos.Draughts))
              },
              apiVersion
            )
          }
        }
      case (Situation.FairySF(_), Variant.FairySF(_)) =>
        (pov.game playableBy pov.player) option
          Event.PossibleMoves.json(pov.game.situation.destinations, apiVersion)
      case (Situation.Samurai(_), Variant.Samurai(_)) =>
        (pov.game playableBy pov.player) option
          Event.PossibleMoves.json(pov.game.situation.destinations, apiVersion)
      case (Situation.Togyzkumalak(_), Variant.Togyzkumalak(_)) =>
        (pov.game playableBy pov.player) option
          Event.PossibleMoves.json(pov.game.situation.destinations, apiVersion)
      case _ => sys.error("Mismatch of types for possibleMoves")
    }

  private def possibleDropsByrole(pov: Pov): Option[JsValue] =
    (pov.game.situation, pov.game.variant) match {
      case (Situation.Chess(_), Variant.Chess(_)) => None
      case (Situation.FairySF(_), Variant.FairySF(_)) =>
        (pov.game playableBy pov.player) option
          Event.PossibleDropsByRole.json(pov.game.situation.dropsByRole.getOrElse(Map.empty))
      case (Situation.Samurai(_), Variant.Samurai(_))           => None
      case (Situation.Togyzkumalak(_), Variant.Togyzkumalak(_)) => None
      case (Situation.Draughts(_), Variant.Draughts(_))         => None
      case _                                                    => sys.error("Mismatch of types for possibleDropsByrole")
    }

  private def possibleDrops(pov: Pov): Option[JsValue] =
    (pov.game playableBy pov.player) ?? {
      pov.game.situation.drops map { drops =>
        JsString(drops.map(_.key).mkString)
      }
    }

  //draughts
  private def captureLength(pov: Pov): Int =
    (pov.game.situation, pov.game.variant) match {
      case (Situation.Draughts(situation), Variant.Draughts(variant)) =>
        if (situation.ghosts > 0) {
          val move    = pov.game.pgnMoves(pov.game.pgnMoves.length - 1)
          val destPos = variant.boardSize.pos.posAt(move.substring(move.lastIndexOf('x') + 1))
          destPos match {
            case Some(dest) => ~situation.captureLengthFrom(dest)
            case _          => situation.allMovesCaptureLength
          }
        } else
          situation.allMovesCaptureLength
      case _ => 0
    }

  private def animationMillis(pov: Pov, pref: Pref) =
    pref.animationMillis * {
      if (pov.game.finished) 1
      else math.max(0, math.min(1.2, ((pov.game.estimateTotalTime - 60) / 60) * 0.2))
    }
}

object JsonView {

  case class WithFlags(
      opening: Boolean = false,
      movetimes: Boolean = false,
      division: Boolean = false,
      clocks: Boolean = false,
      blurs: Boolean = false
  )
}
