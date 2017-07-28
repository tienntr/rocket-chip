// See LICENSE.SiFive for license details.

package freechips.rocketchip.diplomacy

import Chisel._
import chisel3.internal.sourceinfo.SourceInfo
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.HeterogeneousBag
import scala.collection.mutable.ListBuffer

// DI = Downwards flowing Parameters received on the inner side of the node
// UI = Upwards   flowing Parameters generated by the inner side of the node
// EI = Edge Parameters describing a connection on the inner side of the node
// BI = Bundle type used when connecting to the inner side of the node
trait InwardNodeImp[DI, UI, EI, BI <: Data]
{
  def edgeI(pd: DI, pu: UI): EI
  def bundleI(ei: EI): BI
  def colour: String
  def reverse: Boolean = false
  def connect(edges: () => Seq[EI], bundles: () => Seq[(BI, BI)], enableMonitoring: Boolean)
              (implicit p: Parameters, sourceInfo: SourceInfo): (Option[MonitorBase], () => Unit) = {
    (None, () => bundles().foreach { case (i, o) => i <> o })
  }

  // optional methods to track node graph
  def mixI(pu: UI, node: InwardNode[DI, UI, BI]): UI = pu // insert node into parameters
  def getO(pu: UI): Option[BaseNode] = None // most-outward common node
  def labelI(ei: EI) = ""
}

// DO = Downwards flowing Parameters generated by the outer side of the node
// UO = Upwards   flowing Parameters received on the outer side of the node
// EO = Edge Parameters describing a connection on the outer side of the node
// BO = Bundle type used when connecting to the outer side of the node
trait OutwardNodeImp[DO, UO, EO, BO <: Data]
{
  def edgeO(pd: DO, pu: UO): EO
  def bundleO(eo: EO): BO

  // optional methods to track node graph
  def mixO(pd: DO, node: OutwardNode[DO, UO, BO]): DO = pd // insert node into parameters
  def getI(pd: DO): Option[BaseNode] = None // most-inward common node
  def labelO(eo: EO) = ""
}

abstract class NodeImp[D, U, EO, EI, B <: Data]
  extends Object with InwardNodeImp[D, U, EI, B] with OutwardNodeImp[D, U, EO, B]

abstract class BaseNode
{
  require (!LazyModule.stack.isEmpty, "You cannot create a node outside a LazyModule!")

  val lazyModule = LazyModule.stack.head
  val index = lazyModule.nodes.size
  lazyModule.nodes = this :: lazyModule.nodes

  val externalIn: Boolean
  val externalOut: Boolean

  def nodename = getClass.getName.split('.').last
  def name = lazyModule.name + "." + nodename
  def omitGraphML = outputs.isEmpty && inputs.isEmpty
  lazy val nodedebugstring: String = ""

  protected[diplomacy] def gci: Option[BaseNode] // greatest common inner
  protected[diplomacy] def gco: Option[BaseNode] // greatest common outer
  protected[diplomacy] def outputs: Seq[(BaseNode, String)]
  protected[diplomacy] def inputs:  Seq[(BaseNode, String)]
  protected[diplomacy] def colour:  String
  protected[diplomacy] def reverse: Boolean
}

case class NodeHandle[DI, UI, BI <: Data, DO, UO, BO <: Data]
  (inward: InwardNode[DI, UI, BI], outward: OutwardNode[DO, UO, BO])
  extends Object with InwardNodeHandle[DI, UI, BI] with OutwardNodeHandle[DO, UO, BO]

trait InwardNodeHandle[DI, UI, BI <: Data]
{
  val inward: InwardNode[DI, UI, BI]
  def := (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] =
    inward.:=(h)(p, sourceInfo)
  def :*= (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] =
    inward.:*=(h)(p, sourceInfo)
  def :=* (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] =
    inward.:=*(h)(p, sourceInfo)
}

sealed trait NodeBinding
case object BIND_ONCE  extends NodeBinding
case object BIND_QUERY extends NodeBinding
case object BIND_STAR  extends NodeBinding

trait InwardNode[DI, UI, BI <: Data] extends BaseNode with InwardNodeHandle[DI, UI, BI]
{
  val inward = this

  protected[diplomacy] val numPI: Range.Inclusive
  require (!numPI.isEmpty, s"No number of inputs would be acceptable to ${name}${lazyModule.line}")
  require (numPI.start >= 0, s"${name} accepts a negative number of inputs${lazyModule.line}")

  private val accPI = ListBuffer[(Int, OutwardNode[DI, UI, BI], NodeBinding)]()
  private var iRealized = false

  protected[diplomacy] def iPushed = accPI.size
  protected[diplomacy] def iPush(index: Int, node: OutwardNode[DI, UI, BI], binding: NodeBinding)(implicit sourceInfo: SourceInfo) {
    val info = sourceLine(sourceInfo, " at ", "")
    val noIs = numPI.size == 1 && numPI.contains(0)
    require (!noIs, s"${name}${lazyModule.line} was incorrectly connected as a sink" + info)
    require (!iRealized, s"${name}${lazyModule.line} was incorrectly connected as a sink after it's .module was used" + info)
    accPI += ((index, node, binding))
  }

  protected[diplomacy] lazy val iBindings = { iRealized = true; accPI.result() }

  protected[diplomacy] val iStar: Int
  protected[diplomacy] val iPortMapping: Seq[(Int, Int)]
  protected[diplomacy] val iParams: Seq[UI]
  val bundleIn: HeterogeneousBag[BI]
}

trait OutwardNodeHandle[DO, UO, BO <: Data]
{
  val outward: OutwardNode[DO, UO, BO]
}

trait OutwardNode[DO, UO, BO <: Data] extends BaseNode with OutwardNodeHandle[DO, UO, BO]
{
  val outward = this

  protected[diplomacy] val numPO: Range.Inclusive
  require (!numPO.isEmpty, s"No number of outputs would be acceptable to ${name}${lazyModule.line}")
  require (numPO.start >= 0, s"${name} accepts a negative number of outputs${lazyModule.line}")

  private val accPO = ListBuffer[(Int, InwardNode [DO, UO, BO], NodeBinding)]()
  private var oRealized = false

  protected[diplomacy] def oPushed = accPO.size
  protected[diplomacy] def oPush(index: Int, node: InwardNode [DO, UO, BO], binding: NodeBinding)(implicit sourceInfo: SourceInfo) {
    val info = sourceLine(sourceInfo, " at ", "")
    val noOs = numPO.size == 1 && numPO.contains(0)
    require (!noOs, s"${name}${lazyModule.line} was incorrectly connected as a source" + info)
    require (!oRealized, s"${name}${lazyModule.line} was incorrectly connected as a source after it's .module was used" + info)
    accPO += ((index, node, binding))
  }

  protected[diplomacy] lazy val oBindings = { oRealized = true; accPO.result() }

  protected[diplomacy] val oStar: Int
  protected[diplomacy] val oPortMapping: Seq[(Int, Int)]
  protected[diplomacy] val oParams: Seq[DO]
  val bundleOut: HeterogeneousBag[BO]
}

abstract class MixedNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  protected[diplomacy] val numPO: Range.Inclusive,
  protected[diplomacy] val numPI: Range.Inclusive)
  extends BaseNode with InwardNode[DI, UI, BI] with OutwardNode[DO, UO, BO]
{
  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStar: Int, oStar: Int): (Int, Int)
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO]
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI]

  protected[diplomacy] lazy val (oPortMapping, iPortMapping, oStar, iStar) = {
    val oStars = oBindings.filter { case (_,_,b) => b == BIND_STAR }.size
    val iStars = iBindings.filter { case (_,_,b) => b == BIND_STAR }.size
    val oKnown = oBindings.map { case (_, n, b) => b match {
      case BIND_ONCE  => 1
      case BIND_QUERY => n.iStar
      case BIND_STAR  => 0 }}.foldLeft(0)(_+_)
    val iKnown = iBindings.map { case (_, n, b) => b match {
      case BIND_ONCE  => 1
      case BIND_QUERY => n.oStar
      case BIND_STAR  => 0 }}.foldLeft(0)(_+_)
    val (iStar, oStar) = resolveStar(iKnown, oKnown, iStars, oStars)
    val oSum = oBindings.map { case (_, n, b) => b match {
      case BIND_ONCE  => 1
      case BIND_QUERY => n.iStar
      case BIND_STAR  => oStar }}.scanLeft(0)(_+_)
    val iSum = iBindings.map { case (_, n, b) => b match {
      case BIND_ONCE  => 1
      case BIND_QUERY => n.oStar
      case BIND_STAR  => iStar }}.scanLeft(0)(_+_)
    val oTotal = oSum.lastOption.getOrElse(0)
    val iTotal = iSum.lastOption.getOrElse(0)
    require(numPO.contains(oTotal), s"${name} has ${oTotal} outputs, expected ${numPO}${lazyModule.line}")
    require(numPI.contains(iTotal), s"${name} has ${iTotal} inputs, expected ${numPI}${lazyModule.line}")
    (oSum.init zip oSum.tail, iSum.init zip iSum.tail, oStar, iStar)
  }

  lazy val oPorts = oBindings.flatMap { case (i, n, _) =>
    val (start, end) = n.iPortMapping(i)
    (start until end) map { j => (j, n) }
  }
  lazy val iPorts = iBindings.flatMap { case (i, n, _) =>
    val (start, end) = n.oPortMapping(i)
    (start until end) map { j => (j, n) }
  }

  protected[diplomacy] lazy val oParams: Seq[DO] = {
    val o = mapParamsD(oPorts.size, iPorts.map { case (i, n) => n.oParams(i) })
    require (o.size == oPorts.size, s"Bug in diplomacy; ${name} has ${o.size} != ${oPorts.size} down/up outer parameters${lazyModule.line}")
    o.map(outer.mixO(_, this))
  }
  protected[diplomacy] lazy val iParams: Seq[UI] = {
    val i = mapParamsU(iPorts.size, oPorts.map { case (o, n) => n.iParams(o) })
    require (i.size == iPorts.size, s"Bug in diplomacy; ${name} has ${i.size} != ${iPorts.size} up/down inner parameters${lazyModule.line}")
    i.map(inner.mixI(_, this))
  }

  protected[diplomacy] def gco = if (iParams.size != 1) None else inner.getO(iParams(0))
  protected[diplomacy] def gci = if (oParams.size != 1) None else outer.getI(oParams(0))

  lazy val edgesOut = (oPorts zip oParams).map { case ((i, n), o) => outer.edgeO(o, n.iParams(i)) }
  lazy val edgesIn  = (iPorts zip iParams).map { case ((o, n), i) => inner.edgeI(n.oParams(o), i) }
  lazy val externalEdgesOut = if (externalOut) {edgesOut} else { Seq() }
  lazy val externalEdgesIn = if (externalIn) {edgesIn} else { Seq() }

  val flip = false // needed for blind nodes
  private def flipO(b: HeterogeneousBag[BO]) = if (flip) b.flip else b
  private def flipI(b: HeterogeneousBag[BI]) = if (flip) b      else b.flip
  val wire = false // needed if you want to grab access to from inside a module
  private def wireO(b: HeterogeneousBag[BO]) = if (wire) Wire(b) else b
  private def wireI(b: HeterogeneousBag[BI]) = if (wire) Wire(b) else b

  lazy val bundleOut = wireO(flipO(HeterogeneousBag(edgesOut.map(outer.bundleO(_)))))
  lazy val bundleIn  = wireI(flipI(HeterogeneousBag(edgesIn .map(inner.bundleI(_)))))

  // connects the outward part of a node with the inward part of this node
  private def bind(h: OutwardNodeHandle[DI, UI, BI], binding: NodeBinding, enableMonitoring: Boolean)
                  (implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] = {
    val x = this // x := y
    val y = h.outward
    val info = sourceLine(sourceInfo, " at ", "")
    require (!LazyModule.stack.isEmpty, s"${y.name} cannot be connected to ${x.name} outside of LazyModule scope" + info)
    val i = x.iPushed
    val o = y.oPushed
    y.oPush(i, x, binding match {
      case BIND_ONCE  => BIND_ONCE
      case BIND_STAR  => BIND_QUERY
      case BIND_QUERY => BIND_STAR })
    x.iPush(o, y, binding)
    def edges() = {
      val (iStart, iEnd) = x.iPortMapping(i)
      val (oStart, oEnd) = y.oPortMapping(o)
      require (iEnd - iStart == oEnd - oStart, s"Bug in diplomacy; ${iEnd-iStart} != ${oEnd-oStart} means port resolution failed")
      Seq.tabulate(iEnd - iStart) { j => x.edgesIn(iStart+j) }
    }
    def bundles() = {
      val (iStart, iEnd) = x.iPortMapping(i)
      val (oStart, oEnd) = y.oPortMapping(o)
      require (iEnd - iStart == oEnd - oStart, s"Bug in diplomacy; ${iEnd-iStart} != ${oEnd-oStart} means port resolution failed")
      Seq.tabulate(iEnd - iStart) { j =>
        (x.bundleIn(iStart+j), y.bundleOut(oStart+j))
      }
    }
    val (out, newbinding) = inner.connect(edges _, bundles _, enableMonitoring)
    LazyModule.stack.head.bindings = newbinding :: LazyModule.stack.head.bindings
    out
  }

  override def :=  (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] = bind(h, BIND_ONCE, true)
  override def :*= (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] = bind(h, BIND_STAR, true)
  override def :=* (h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] = bind(h, BIND_QUERY, true)

  def connectButDontMonitor(h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] = bind(h, BIND_ONCE, false)
  def connectButDontMonitorSlaves(h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] = bind(h, BIND_STAR, false)
  def connectButDontMonitorMasters(h: OutwardNodeHandle[DI, UI, BI])(implicit p: Parameters, sourceInfo: SourceInfo): Option[MonitorBase] = bind(h, BIND_QUERY, false)

  // meta-data for printing the node graph
  protected[diplomacy] def colour  = inner.colour
  protected[diplomacy] def reverse = inner.reverse
  protected[diplomacy] def outputs = oPorts.map(_._2) zip edgesOut.map(e => outer.labelO(e))
  protected[diplomacy] def inputs  = iPorts.map(_._2) zip edgesIn .map(e => inner.labelI(e))
}

abstract class MixedCustomNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  numPO: Range.Inclusive,
  numPI: Range.Inclusive)
  extends MixedNode(inner, outer)(numPO, numPI)
{
  def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int)
  def mapParamsD(n: Int, p: Seq[DI]): Seq[DO]
  def mapParamsU(n: Int, p: Seq[UO]): Seq[UI]
}

abstract class CustomNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  numPO: Range.Inclusive,
  numPI: Range.Inclusive)
  extends MixedCustomNode(imp, imp)(numPO, numPI)

class MixedAdapterNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  dFn: DI => DO,
  uFn: UO => UI,
  num: Range.Inclusive = 0 to 999)
  extends MixedNode(inner, outer)(num, num)
{
  val externalIn: Boolean = true
  val externalOut: Boolean = true

  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oStars + iStars <= 1, s"${name} (an adapter) appears left of a :*= ${iStars} times and right of a :=* ${oStars} times; at most once is allowed${lazyModule.line}")
    if (oStars > 0) {
      require (iKnown >= oKnown, s"${name} (an adapter) has ${oKnown} outputs and ${iKnown} inputs; cannot assign ${iKnown-oKnown} edges to resolve :=*${lazyModule.line}")
      (0, iKnown - oKnown)
    } else {
      require (oKnown >= iKnown, s"${name} (an adapter) has ${oKnown} outputs and ${iKnown} inputs; cannot assign ${oKnown-iKnown} edges to resolve :*=${lazyModule.line}")
      (oKnown - iKnown, 0)
    }
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = {
    require(n == p.size, s"${name} has ${p.size} inputs and ${n} outputs; they must match${lazyModule.line}")
    p.map(dFn)
  }
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = {
    require(n == p.size, s"${name} has ${n} inputs and ${p.size} outputs; they must match${lazyModule.line}")
    p.map(uFn)
  }
}

class MixedNexusNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  dFn: Seq[DI] => DO,
  uFn: Seq[UO] => UI,
  numPO: Range.Inclusive = 1 to 999,
  numPI: Range.Inclusive = 1 to 999)
  extends MixedNode(inner, outer)(numPO, numPI)
{
//  require (numPO.end >= 1, s"${name} does not accept outputs${lazyModule.line}")
//  require (numPI.end >= 1, s"${name} does not accept inputs${lazyModule.line}")

  val externalIn: Boolean = true
  val externalOut: Boolean = true

  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (iStars == 0, s"${name} (a nexus) appears left of :*= (perhaps you should flip the '*' to :=*?)${lazyModule.line}")
    require (oStars == 0, s"${name} (a nexus) appears right of a :=* (perhaps you should flip the '*' to :*=?)${lazyModule.line}")
    (0, 0)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = { val a = dFn(p); Seq.fill(n)(a) }
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = { val a = uFn(p); Seq.fill(n)(a) }
}

class AdapterNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: D => D,
  uFn: U => U,
  num: Range.Inclusive = 0 to 999)
    extends MixedAdapterNode[D, U, EI, B, D, U, EO, B](imp, imp)(dFn, uFn, num)

class NexusNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: Seq[D] => D,
  uFn: Seq[U] => U,
  numPO: Range.Inclusive = 1 to 999,
  numPI: Range.Inclusive = 1 to 999)
    extends MixedNexusNode[D, U, EI, B, D, U, EO, B](imp, imp)(dFn, uFn, numPO, numPI)

case class SplitterArg[T](newSize: Int, ports: Seq[T])
class MixedSplitterNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  inner: InwardNodeImp [DI, UI, EI, BI],
  outer: OutwardNodeImp[DO, UO, EO, BO])(
  dFn: SplitterArg[DI] => Seq[DO],
  uFn: SplitterArg[UO] => Seq[UI],
  numPO: Range.Inclusive = 1 to 999,
  numPI: Range.Inclusive = 1 to 999)
  extends MixedNode(inner, outer)(numPO, numPI)
{
  override val externalIn: Boolean = true
  override val externalOut: Boolean = true

  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oKnown == 0, s"${name} (a splitter) appears right of a := or :*=; use a :=* instead${lazyModule.line}")
    require (iStars == 0, s"${name} (a splitter) cannot appear left of a :*=; did you mean :=*?${lazyModule.line}")
    (0, iKnown)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[DI]): Seq[DO] = {
    require (p.size == 0 || n % p.size == 0, s"Diplomacy bug; splitter inputs do not divide outputs")
    val out = dFn(SplitterArg(n, p))
    require (out.size == n, s"${name} created the wrong number of outputs from inputs${lazyModule.line}")
    out
  }
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[UO]): Seq[UI] = {
    require (n == 0 || p.size % n == 0, s"Diplomacy bug; splitter outputs indivisable by inputs")
    val out = uFn(SplitterArg(n, p))
    require (out.size == n, s"${name} created the wrong number of inputs from outputs${lazyModule.line}")
    out
  }
}

class SplitterNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: SplitterArg[D] => Seq[D],
  uFn: SplitterArg[U] => Seq[U],
  numPO: Range.Inclusive = 1 to 999,
  numPI: Range.Inclusive = 1 to 999)
    extends MixedSplitterNode[D, U, EI, B, D, U, EO, B](imp, imp)(dFn, uFn, numPO, numPI)

class IdentityNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])
  extends AdapterNode(imp)({s => s}, {s => s})

class OutputNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B]) extends IdentityNode(imp)
{
  override val externalIn: Boolean = false
  override val externalOut: Boolean = true
  override lazy val bundleIn = bundleOut
}

class InputNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B]) extends IdentityNode(imp)
{
  override val externalIn: Boolean = true
  override val externalOut: Boolean = false

  override lazy val bundleOut = bundleIn
}

class SourceNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(po: Seq[D])
  extends MixedNode(imp, imp)(po.size to po.size, 0 to 0)
{
  override val externalIn: Boolean = false
  override val externalOut: Boolean = true

  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (oStars <= 1, s"${name} (a source) appears right of a :=* ${oStars} times; at most once is allowed${lazyModule.line}")
    require (iStars == 0, s"${name} (a source) cannot appear left of a :*=${lazyModule.line}")
    require (iKnown == 0, s"${name} (a source) cannot appear left of a :=${lazyModule.line}")
    require (po.size >= oKnown, s"${name} (a source) has ${oKnown} outputs out of ${po.size}; cannot assign ${po.size - oKnown} edges to resolve :=*${lazyModule.line}")
    (0, po.size - oKnown)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[D]): Seq[D] = po
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[U]): Seq[U] = Seq()

  override lazy val bundleIn = { require(false, s"${name} has no bundleIn; try bundleOut?"); bundleOut }
}

class SinkNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(pi: Seq[U])
  extends MixedNode(imp, imp)(0 to 0, pi.size to pi.size)
{
  override val externalIn: Boolean = true
  override val externalOut: Boolean = false

  protected[diplomacy] def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    require (iStars <= 1, s"${name} (a sink) appears left of a :*= ${iStars} times; at most once is allowed${lazyModule.line}")
    require (oStars == 0, s"${name} (a sink) cannot appear right of a :=*${lazyModule.line}")
    require (oKnown == 0, s"${name} (a sink) cannot appear right of a :=${lazyModule.line}")
    require (pi.size >= iKnown, s"${name} (a sink) has ${iKnown} inputs out of ${pi.size}; cannot assign ${pi.size - iKnown} edges to resolve :*=${lazyModule.line}")
    (pi.size - iKnown, 0)
  }
  protected[diplomacy] def mapParamsD(n: Int, p: Seq[D]): Seq[D] = Seq()
  protected[diplomacy] def mapParamsU(n: Int, p: Seq[U]): Seq[U] = pi

  override lazy val bundleOut = { require(false, s"${name} has no bundleOut; try bundleIn?"); bundleIn }
}

class BlindOutputNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(pi: Seq[U])
  extends SinkNode(imp)(pi)
{
  override val externalIn: Boolean = false
  override val flip = true
  override lazy val bundleOut = bundleIn
}

class BlindInputNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(po: Seq[D])
  extends SourceNode(imp)(po)
{
  override val externalOut: Boolean = false
  override val flip = true
  override lazy val bundleIn = bundleOut
}

class InternalOutputNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(pi: Seq[U])
  extends SinkNode(imp)(pi)
{
  override val externalIn: Boolean = false
  override val externalOut: Boolean = false
  override val wire = true
  override lazy val bundleOut = bundleIn
}

class InternalInputNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(po: Seq[D])
  extends SourceNode(imp)(po)
{
  override val externalIn: Boolean = false
  override val externalOut: Boolean = false
  override val wire = true
  override lazy val bundleIn = bundleOut
}
