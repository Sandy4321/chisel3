// See LICENSE for license details.

package Chisel.firrtl
import Chisel._

private class Emitter(circuit: Circuit) {
  override def toString: String = res.toString

  private def emitPort(e: Port): String =
    s"${e.dir} ${e.id.getRef.name} : ${e.id.toType}"
  private def emit(e: Command, ctx: Component): String = e match {
    case e: DefPrim[_] => s"node ${e.name} = ${e.op.name}(${e.args.map(_.fullName(ctx)).reduce(_ + ", " + _)})"
    case e: DefWire => s"wire ${e.name} : ${e.id.toType}"
    case e: DefPoison[_] => s"poison ${e.name} : ${e.id.toType}"
    case e: DefRegister => s"reg ${e.name} : ${e.id.toType}, ${e.clock.fullName(ctx)}, ${e.reset.fullName(ctx)}"
    case e: DefMemory => s"cmem ${e.name} : ${e.t.toType}[${e.size}], ${e.clock.fullName(ctx)}"
    case e: DefSeqMemory => s"smem ${e.name} : ${e.t.toType}[${e.size}], ${e.clock.fullName(ctx)}"
    case e: DefAccessor[_] => s"infer accessor ${e.name} = ${e.source.fullName(ctx)}[${e.index.fullName(ctx)}]"
    case e: Connect => s"${e.loc.fullName(ctx)} := ${e.exp.fullName(ctx)}"
    case e: BulkConnect => s"${e.loc1.fullName(ctx)} <> ${e.loc2.fullName(ctx)}"
    case e: ConnectInit => s"onreset ${e.loc.fullName(ctx)} := ${e.exp.fullName(ctx)}"
    case e: Stop => s"stop(${e.clk.fullName(ctx)}, ${e.ret})"
    case e: Printf => s"""printf(${e.clk.fullName(ctx)}, "${e.format}"${e.ids.map(_.fullName(ctx)).fold(""){_ + ", " + _}})"""
    case e: DefInstance => {
      val modName = moduleMap.get(e.id.name).get
      s"inst ${e.name} of $modName"
    }

    case w: WhenBegin =>
      indent()
      s"when ${w.pred.fullName(ctx)} :"
    case _: WhenEnd =>
      unindent()
      "skip"
  }

  // Map of Module FIRRTL definition to FIRRTL name, if it has been emitted already.
  private val defnMap = collection.mutable.HashMap[String, String]()
  // Map of Component name to FIRRTL id.
  private val moduleMap = collection.mutable.HashMap[String, String]()

  /** Generates the FIRRTL module definition with a specified name.
    */
  private def moduleDefn(m: Component, name: String): String = {
    val body = new StringBuilder
    m.id match {
      case _: BlackBox => body ++= newline + s"extmodule $name : "
      case _: Module => body ++= newline + s"module $name : "
    }
    withIndent {
      for (p <- m.ports)
        body ++= newline + emitPort(p)
      body ++= newline

      m.id match {
        case _: BlackBox =>
          // TODO: BlackBoxes should be empty, but funkiness in Module() means
          // it's not for now. Eventually, this should assert out.
        case _: Module => for (cmd <- m.commands) {
          body ++= newline + emit(cmd, m)
        }
      }
      body ++= newline
    }
    body.toString()
  }

  /** Returns the FIRRTL declaration and body of a module, or nothing if it's a
    * duplicate of something already emitted (on the basis of simple string
    * matching).
    */
  private def emit(m: Component): String = {
    // Generate the body.
    val moduleName = m.id.getClass.getName.split('.').last
    val defn = moduleDefn(m, moduleName)

    defnMap get defn match {
      case Some(deduplicatedName) =>
        moduleMap(m.name) = deduplicatedName
        ""
      case None =>
        require(!(moduleMap contains m.name),
            "emitting module with same name but different contents")

        moduleMap(m.name) = m.name
        defnMap(defn) = m.name

        moduleDefn(m, m.name)
    }
  }

  private var indentLevel = 0
  private def newline = "\n" + ("  " * indentLevel)
  private def indent(): Unit = indentLevel += 1
  private def unindent() { require(indentLevel > 0); indentLevel -= 1 }
  private def withIndent(f: => Unit) { indent(); f; unindent() }

  private val res = new StringBuilder(s"circuit ${circuit.name} : ")
  withIndent { circuit.components.foreach(c => res ++= emit(c)) }
  res ++= newline
}