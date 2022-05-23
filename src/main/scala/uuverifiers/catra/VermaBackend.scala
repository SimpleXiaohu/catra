package uuverifiers.catra

import uuverifiers.common.{Automaton, NrTransitionsOrdering}
import ap.SimpleAPI
import SimpleAPI.ProverStatus
import ap.basetypes.LeftistHeap
import ap.terfor.{ConstantTerm, TermOrder}
import uuverifiers.parikh_theory.VariousHelpers.transitionsIncrementRegisters

import scala.annotation.tailrec

private class ProductQueue(private val queue: LeftistHeap[Automaton, _]) {
  def enqueue(a: Automaton): ProductQueue = new ProductQueue(queue + a)
  def isEmpty: Boolean = queue.isEmpty
}

private object ProductQueue {
  def apply(automata: Iterable[Automaton]) =
    new ProductQueue(
      LeftistHeap.EMPTY_HEAP(ord = NrTransitionsOrdering) ++ automata
    )
  def unapply(q: ProductQueue): Option[(Automaton, Automaton, ProductQueue)] = {
    if (q.queue.size >= 2) {
      val first = q.queue.findMin
      val rest1 = q.queue.deleteMin
      val second = rest1.findMin
      Some((first, second, new ProductQueue(rest1.deleteMin)))
    } else None
  }
}
class VermaBackend(override val arguments: CommandLineOptions)
    extends PrincessBasedBackend {

  def handleDumpingGraphviz(a: Automaton): Unit =
    arguments.dumpGraphvizDir.foreach(
      dir => a.dumpDotFile(dir, s"${a.name}.dot")
    )

  private def logDecision(msg: String): Unit = if (arguments.printDecisions) {
    System.err.println(msg)
  }

  override def prepareSolver(
      p: SimpleAPI,
      instance: Instance
  ): Map[Counter, ConstantTerm] = {

    val counterToSolverConstant =
      trace("Counter -> solver constant")(
        instance.counters.map(c => c -> c.toConstant(p)).toMap
      )

    for (constraint <- instance.constraints) {
      p.addAssertion(
        trace("post constraint from input file")(
          constraint toPrincess counterToSolverConstant
        )
      )
    }

    val termsToCheckSat = if (arguments.checkTermSat) {
      instance.automataProducts.flatten
    } else Seq()

    val termsAreSat = checkTermsCoherent(
      p,
      counterToSolverConstant,
      termsToCheckSat
    )
    if (termsAreSat != ProverStatus.Unsat) {
      instance.automataProducts.foreach(
        terms =>
          incrementallyComputeProduct(
            p,
            counterToSolverConstant,
            ProductQueue(terms)
          )
      )
    }
    counterToSolverConstant
  }

  @tailrec
  private def checkTermsCoherent(
      p: SimpleAPI,
      counterToSolverConstant: Map[Counter, ConstantTerm],
      remainingTerms: Seq[Automaton]
  ): SimpleAPI.ProverStatus.Value = remainingTerms match {
    case Seq() => p.checkSat(block = true)
    case fst +: rest =>
      postParikhSat(p, counterToSolverConstant, fst)
      val satStatus = trace("term SAT check")(
        p.checkSat(block = true)
      )
      logDecision(s"Term ${fst.name} satisfiable? $satStatus")
      if (satStatus != ProverStatus.Unsat) {
        checkTermsCoherent(p, counterToSolverConstant, rest)
      } else {
        satStatus
      }
  }

  private def postParikhSat(
      p: SimpleAPI,
      counterToSolverConstant: Map[Counter, ConstantTerm],
      newProduct: Automaton
  ): Unit = {
    implicit val order: TermOrder = p.order

    p.addAssertion(
      trace("partial product Parikh image")(
        newProduct.parikhImage(
          transitionsIncrementRegisters(newProduct, counterToSolverConstant)(_),
          quantElim = arguments.eliminateQuantifiers
        )
      )
    )
  }

  private def computeProductStep(
      left: Automaton,
      right: Automaton
  ): Automaton = {
    val newProduct = left productWith right
    handleDumpingGraphviz(newProduct)
    ap.util.Timeout.check
    newProduct
  }

  @tailrec
  private def incrementallyComputeProduct(
      p: SimpleAPI,
      counterToSolverConstant: Map[Counter, ConstantTerm],
      automataLeft: ProductQueue
  ): Unit = automataLeft match {
    case ProductQueue(first, second, rest) =>
      val product = computeProductStep(first, second)

      logDecision(
        s"""Computed product ${product.name}.
           |\tSize of terms: ${first.transitions.size}, ${second.transitions.size}
           |\tSize of product: ${product.transitions.size}""".stripMargin
      )

      if (rest.isEmpty || arguments.checkIntermediateSat) {
        postParikhSat(p, counterToSolverConstant, product)
      }

      val stillSatisfiable = trace("product SAT check")(
        p.checkSat(block = true)
      ) != ProverStatus.Unsat

      logDecision(s"Satisfiable? $stillSatisfiable")
      if (stillSatisfiable) {
        incrementallyComputeProduct(
          p,
          counterToSolverConstant,
          rest.enqueue(product)
        )
      }
    case _ =>
  }
}