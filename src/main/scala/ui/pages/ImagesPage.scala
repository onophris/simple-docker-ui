package ui.pages

import api.DockerClient
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactEventI}
import model._
import ui.WorkbenchRef
import ui.widgets.PullModalDialog.ActionsBackend
import ui.widgets.{Alert, Button, InfoCard, PullModalDialog}
import util.StringUtils._
import util.logger._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ImagesPage extends Page {

  val id = "Images"

  case class State(localImages: Seq[Image] = Seq.empty,
                   remoteImages: Seq[ImageSearch] = Seq.empty,
                   searching: Boolean = false,
                   searchText: String = "",
                   imageToPull: Option[ImageSearch] = None,
                   deleting: Boolean = false,
                   error: Option[String] = None) {
    def searchIcon =
      if (searching)
        "glyphicon glyphicon-refresh glyphicon-spin"
      else
        "glyphicon glyphicon-search"

    def totalSize = bytesToSize(localImages.map(_.VirtualSize.toLong).sum)
  }

  case class Props(ref: WorkbenchRef)

  case class Backend(t: BackendScope[Props, State]) extends ActionsBackend {
    def willMount(): Unit = t.props.ref.client.map { client =>
      client.images().map { images =>
        t.modState(s => s.copy(localImages = images))
      }.onFailure {
        case ex: Exception =>
          log.error("ImagesPage", "Unable to get Metadata", ex)
          t.modState(s => s.copy(error = Some(s"Unable to connect")))
      }
    }

    val MinTextSize = 2

    def onTextChange(e: ReactEventI): Unit = t.props.ref.client.map { client =>
      val text = e.target.value
      val searching = t.state.searching
      if (text.isEmpty) {
        t.modState(s => s.copy(remoteImages = Seq.empty, searching = false))
      } else if (!searching && text.length > MinTextSize) {
        t.modState(s => s.copy(searching = true, searchText = text))
        search(client, text)
      } else {
        t.modState(s => s.copy(searchText = text))
      }
    }

    def search(client: DockerClient, text: String): Unit = {
      client.imagesSearch(text).map { images =>
        log.info(s"images ${images.size}")
        t.modState(_.copy(remoteImages = images))
        if (t.state.searchText != text)
          search(client, t.state.searchText)
        else
          t.modState(_.copy(searching = false))
      }.onFailure {
        case ex: Exception =>
          log.error("ImagesPage", "Unable to get Metadata", ex)
          t.modState(s => s.copy(error = Some(s"Unable to connect")))
      }
    }

    def showDetail(image: ImageSearch) = {
      t.modState(s => s.copy(imageToPull = Some(image)))
    }

    def refresh() = willMount()

    override def imagePulled(): Unit = refresh()

    def garbageCollection(): Future[Unit] = t.props.ref.client.get.garbageCollectionImages().map { images =>
      t.modState(s => s.copy(localImages = images))
    }
  }

  def component(ref: WorkbenchRef) = {
    val props = Props(ref)
    ImagesPageRender.component(props)
  }
}


object ImagesPageRender {

  import ui.pages.ImagesPage._

  val component = ReactComponentB[Props]("ImagesPage")
    .initialState(State())
    .backend(new Backend(_))
    .render((P, S, B) => vdom(S, P, B))
    .componentWillMount(_.backend.willMount)
    .build


  def vdom(S: State, P: Props, B: Backend) = <.div(
    S.error.map(Alert(_)),
    remoteSearch(S, P, B),
    table("Local images", S, P, B),
    S.imageToPull.map(image => PullModalDialog(B, image, P.ref))
  )

  def table(title: String, S: State, P: Props, B: Backend) =
    <.div(^.className := "container  col-sm-12",
      <.div(^.className := "panel panel-default  bootcards-summary",
        <.div(^.className := "panel-heading clearfix",
          <.h3(^.className := "panel-title pull-left")(<.span(^.className := "glyphicon glyphicon-hdd"), " " + title)
        ),
        <.table(^.className := "table table-hover table-striped",
          <.thead(
            <.tr(
              <.th("Id"),
              <.th("Tags"),
              <.th("Created"),
              <.th("Size")
            )
          ),
          <.tbody(
            S.localImages.map { img =>
              <.tr(
                <.td(P.ref.link(ImagePage(img, P.ref))(img.id)),
                <.td(img.RepoTags.mkString(", ")),
                <.td(img.created),
                <.td(img.virtualSize)
              )
            }
          )
        )
      )
    )


  def vdomCommands(S: State, B: Backend) =
    Some(<.div(^.className := "panel-footer",
      <.div(^.className := "btn-group btn-group-justified",
        <.div(^.className := "btn-group",
          Button("Garbage Collection", "glyphicon-trash")(B.garbageCollection)
        )
      )
    ))

  def remoteSearch(S: State, P: Props, B: Backend) =
    <.div(
      <.div(^.className := "container  col-sm-8",
        <.div(^.className := "bootcards-list ",
          <.div(^.className := "panel panel-default",
            <.div(^.className := "panel-body",
              <.form(^.className := "form-horizontal",
                <.div(^.className := "form-group",
                  <.label(^.className := "col-sm-3 control-label")(<.span(^.className := S.searchIcon), " Registry Hub"),
                  <.div(^.className := "col-sm-9",
                    <.input(^.`type` := "text", ^.className := "form-control", ^.placeholder := "Search Images...", ^.onChange ==> B.onTextChange)
                  )
                )
              )
            ),
            remoteList(S, P, B)
          )
        )
      ),
      imagesInfo(S, B)
    )

  def imagesInfo(S: State, B: Backend) = {
    val info = Map(
      "Total Size" -> S.totalSize
    )
    InfoCard(info, InfoCard.SMALL, None, Seq.empty, vdomCommands(S, B))
  }

  var data_toggle = "data-toggle".reactAttr
  val data_target = "data-target".reactAttr


  def remoteList(S: State, P: Props, B: Backend) =
    <.div(^.className := "list-group",
      S.remoteImages.map { image =>
        <.a(^.className := "list-group-item", ^.onClick --> B.showDetail(image), data_toggle := "modal", data_target := "#editModal",
          image.is_official ?= <.i(^.className := "glyphicon glyphicon-bookmark pull-left"),
          (image.star_count == 0) ?= <.i(^.className := "glyphicon glyphicon-star-empty pull-right")(image.star_count),
          (image.star_count > 0) ?= <.i(^.className := "glyphicon glyphicon-star pull-right")(image.star_count),
          <.h4(^.className := "list-group-item-heading", image.name),
          <.p(^.className := "list-group-item-text", image.description)
        )

      }
    )


}