package io.hydrosphere.serving.repository.datasource.local

import java.io.File
import java.nio.file.StandardWatchEventKinds._
import java.nio.file._

import akka.actor.{ActorRef, Props}
import io.hydrosphere.serving.repository.Messages
import io.hydrosphere.serving.repository.datasource.SourceWatcher
import io.hydrosphere.serving.repository.util.FileUtils._

import scala.collection.JavaConverters._
import scala.collection.mutable


/**
  * Created by Bulat on 31.05.2017.
  */
class LocalSourceWatcher(val source: LocalSource, val indexer: ActorRef) extends SourceWatcher {
  private[this] val watcher = FileSystems.getDefault.newWatchService()
  private[this] val keys = mutable.Map.empty[WatchKey, Path]

  private def subscribeRecursively(dirFile: File): Unit = {
    val maxDepth = 2
    def _subscribe(dirFile: File, depth: Long = 0): Unit = {
      if (depth <= maxDepth) {
        val dirPath = Paths.get(dirFile.getCanonicalPath).normalize()
        keys += dirPath.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) -> dirPath
        dirFile.getSubDirectories.foreach(d => _subscribe(d, depth+1))
      }
    }
    _subscribe(dirFile)
  }

  private def handleWatcherEvent[T](path: Path, event: WatchEvent[T]) = {
    event.kind() match {
      case StandardWatchEventKinds.OVERFLOW =>
        log.warning(s"File system event: Overflow: $event")

      case StandardWatchEventKinds.ENTRY_CREATE =>
        val name = event.context().asInstanceOf[Path]
        val child = path.resolve(name)
        log.debug(s"File system event: ENTRY_CREATE: $child")
        indexer ! Messages.Watcher.ChangeDetected

      case StandardWatchEventKinds.ENTRY_MODIFY =>
        val name = event.context().asInstanceOf[Path]
        val child = path.resolve(name)
        log.debug(s"File system event: ENTRY_MODIFY: $child")
        indexer ! Messages.Watcher.ChangeDetected

      case StandardWatchEventKinds.ENTRY_DELETE =>
        val name = event.context().asInstanceOf[Path]
        val child = path.resolve(name)
        log.debug(s"File system event: ENTRY_DELETE: $child")
        indexer ! Messages.Watcher.ChangeDetected

      case x =>
        log.debug(s"File system event: Unknown: $x")
    }
  }

  override def preStart(): Unit = {
    subscribeRecursively(source.sourceFile)
  }

  override def watcherPostStop(): Unit = {
    keys.keys.foreach(_.cancel())
    watcher.close()
  }

  override def onWatcherTick(): Unit = {
    for {
      (key, path) <- keys
      event <- key.pollEvents().asScala
    } {
      handleWatcherEvent(path, event)
    }
  }
}

object LocalSourceWatcher {
  def props(localSource: LocalSource, indexer: ActorRef) = Props(classOf[LocalSourceWatcher], localSource, indexer)
}