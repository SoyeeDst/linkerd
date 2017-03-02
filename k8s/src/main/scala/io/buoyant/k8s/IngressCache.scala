package io.buoyant.k8s

import com.twitter.finagle.Path
import com.twitter.util.Future

case class NamespacedName(
  namespace: Option[String],
  name: Option[String]
)

case class IngressSpec(
  name: Option[String],
  fallbackBackend: Option[IngressPath] = None,
  rules: Seq[IngressPath]
)

case class IngressPath(
  host: Option[String] = None,
  path: Option[Path] = None,
  namespace: String,
  svc: String,
  port: String
)

/**
  * IngressCache watches for ingress changes
  * and checks incoming requests against cached ingress rules
  *
  * @param namespace: the k8s namespace to filter on
  */

class IngressCache(namespace: String)
  extends Ns.NsListCache[v1beta1.Ingress, v1beta1.IngressWatch, v1beta1.IngressList, IngressSpec, NamespacedName](namespace) {
  def update(watch: v1beta1.IngressWatch): Unit = watch match {
    case v1beta1.IngressError(e) => log.error("k8s watch error: %s", e)
    case v1beta1.IngressAdded(ingress) => add(ingress)
    case v1beta1.IngressModified(ingress) => modify(ingress)
    case v1beta1.IngressDeleted(ingress) => delete(ingress)
  }

  def getName(ingress: v1beta1.Ingress): Option[NamespacedName] =
    ingress.metadata.flatMap { i =>
      (i.namespace, i.name) match {
        case (Some(ns), Some(n)) => Some(NamespacedName(i.namespace, i.name))
        case _ => None
      }
    }

  def updateItem(ingress: v1beta1.Ingress): Option[IngressSpec] = mkItem(ingress)

  def mkItem(ingress: v1beta1.Ingress): Option[IngressSpec] = {
    val namespace = ingress.metadata.flatMap(meta => meta.namespace).getOrElse("default")
    ingress.spec match {
      case Some(spec) =>
        val paths = for (
          spec <- ingress.spec.toSeq;
          rules <- spec.rules.toSeq;
          rule <- rules;
          http <- rule.http.toSeq;
          path <- http.paths
        ) yield {
          IngressPath(rule.host, path.path.map(Path.read(_)), namespace, path.backend.serviceName, path.backend.servicePort)
        }

        val fallback = spec.backend.map(b => IngressPath(None, None, namespace, b.serviceName, b.servicePort))
        Some(IngressSpec(ingress.metadata.flatMap(meta => meta.name), fallback, paths))
      case None => None
    }
  }

  def isPrefix(pfx: Path, str: Path):Boolean =
    pfx.showElems.corresponds(str.take(pfx.size).showElems) { _ == _ }

  def getMatchingPath(hostHeader: Option[String], requestPath: String): Future[Option[IngressPath]] = {
    val pathToMatch = Path.read(requestPath)
    items.map { cache =>
      cache.values.flatMap { ingressResource =>
        val resourceSample = ingressResource.sample()
        val matchingPath = resourceSample.rules.find { rule =>
          (rule.host, rule.path) match {
            case (Some(host), Some(path)) => isPrefix(path, pathToMatch) && hostHeader.map(h => h == host).getOrElse(false)
            case (Some(host), None) => hostHeader.map(h => h == host).getOrElse(false)
            case (None, Some(path)) => isPrefix(path, pathToMatch)
            case (None, None) => false
          }
        }
        (matchingPath, resourceSample.fallbackBackend) match {
          case (Some(path), _) =>
            log.info("k8s found rule matching %s %s: %s", hostHeader.getOrElse(""), requestPath, path)
            Some(path)
          case (None, Some(default)) =>
            log.info("k8s using default service %s for request %s %s", default, hostHeader.getOrElse(""), requestPath)
            Some(default)
          case _ =>
            log.info("k8s no suitable rule found in %s for request %s %s", resourceSample.name.getOrElse(""), hostHeader.getOrElse(""), requestPath)
            None
        }
      }.headOption
    }.values.toFuture.flatMap(Future.const)
  }
}
