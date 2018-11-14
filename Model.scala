package model

import com.microsoft.z3
import java.util.HashMap
import scala.collection.immutable._
import scala.util.control.Breaks._ 
import scala.language.postfixOps

import scala.collection.mutable.Map
import scala.collection.JavaConverters._
import com.microsoft.z3.enumerations.Z3_lbool
import com.microsoft.z3.enumerations.Z3_decl_kind
import java.io.File
import java.io.PrintWriter

class TransitionSystem(suff:String=""){

  val cfg = new HashMap[String, String]();
  cfg.put("model", "true");
  val ctx = new z3.Context(cfg);
  var suffix = suff;
  var variables = List(ctx.mkInt("x"), ctx.mkInt("y"));
  if(suff !=""){
    variables = List(ctx.mkInt("x_" + suffix), ctx.mkInt("y_" + suffix));
  }
  var sorts = List(ctx.mkIntSort, ctx.mkIntSort);

  def addsuffix(suff:String=""):List[z3.ArithExpr] = {
    var s = "";
    if(suff!=""){
      s = "_"+suff;
    }
    return List(ctx.mkInt("x"+s), ctx.mkInt("y"+s));
    //List(1, 2);
  }

  def initialize(xs:List[z3.ArithExpr]):List[z3.BoolExpr] = {
    return List(ctx.mkEq(xs(0), ctx.mkInt(0)), ctx.mkEq(xs(1), ctx.mkInt(1)));
  }

  def transition(xs:List[z3.ArithExpr]):List[z3.ArithExpr] = {
    return List(ctx.mkAdd(xs(0), ctx.mkInt(1)), ctx.mkAdd(xs(0), xs(1)));
  }
}

class SelfComposedTransitionSystem(modelarg:TransitionSystem){

  val ctx = modelarg.ctx;
  var model = modelarg;
  var vm = modelarg.addsuffix("1");
  var vmprime = modelarg.addsuffix("2");
  var variables = vm ::: vmprime;
  var sorts = modelarg.sorts ::: modelarg.sorts
  var arity = 4;

  def addsuffix(suff:String=""):List[z3.ArithExpr] = {
    var v1 = this.model.addsuffix("1"+suff);
    var v2 = this.model.addsuffix("2"+suff);
    return v1 ::: v2;
  }

  def initialize(xs:List[z3.ArithExpr]):List[z3.BoolExpr] = {
    return this.model.initialize(xs.slice(0, xs.size/2)) ::: this.model.initialize(xs.slice(xs.size/2, xs.size))
  }

  def transition(xs:List[z3.ArithExpr]):List[z3.ArithExpr] = {
    return this.model.transition(xs.slice(0, xs.size/2)) ::: this.model.transition(xs.slice(xs.size/2, xs.size))
  }

  def bad_sc(xs:List[z3.ArithExpr]):List[z3.BoolExpr] = {
    return List(ctx.mkAnd(ctx.mkEq(xs(0), xs(2)), ctx.mkDistinct(xs(1), xs(3))))
  }

}

class CheckModel(){

  def relationalInduction(){

    var m = new TransitionSystem();
    var msc = new SelfComposedTransitionSystem(m);
    val cfg = new HashMap[String, String]();
    cfg.put("model", "true");
    cfg.put("proof", "true");
    val ctx = z3.InterpolationContext.mkContext(cfg);
    
    var xs = msc.variables;
    var xst = msc.transition(xs);
    var xsp = msc.addsuffix("prime");

    var bad = ctx.mkAnd(msc.bad_sc(xs):_*).simplify().asInstanceOf[z3.BoolExpr];
    var init = ctx.mkAnd(msc.initialize(xs):_*).simplify().asInstanceOf[z3.BoolExpr];
    var check = ctx.mkAnd(init, bad).simplify().asInstanceOf[z3.BoolExpr];

    var solver = ctx.mkSolver();

    solver.push();
    solver.add(check);
    var rinit = solver.check();
    solver.pop();
    assert(rinit == z3.Status.UNSATISFIABLE)

    solver.push();

    var bad_proofob = ctx.mkAnd(msc.bad_sc(xsp):_*).simplify().asInstanceOf[z3.BoolExpr];
    var trx = true.asInstanceOf[z3.BoolExpr];
    for (i <- 0 until msc.arity){
      trx = ctx.mkAnd(trx, ctx.mkEq(xsp(i), xst(i))).simplify().asInstanceOf[z3.BoolExpr];
    }

    solver.add(bad);
    solver.add(trx);
    solver.add(bad_proofob);

    var n = xs.size/2;

    breakable{
      while(solver.check() == z3.Status.SATISFIABLE){
        breakable{
          var model = solver.getModel();
          var xm1 = xs.slice(0, xs.size/2).map(xsi => model.eval(xsi, true))
          var xm2 = xs.slice(xs.size/2, xs.size).map(xsi => model.eval(xsi, true))
          val range = 0 until xs.size toList;
          var bad1 = (xs1:List[z3.ArithExpr]) => List(ctx.mkAnd((range.map((i=>ctx.mkEq(xs1(i), xm1(i))))):_*));
          var bad2 = (xs2:List[z3.ArithExpr]) => List(ctx.mkAnd((range.map((i=>ctx.mkEq(xs2(i), xm2(i))))):_*));

          // These 3 values are returned by the getLength function
          var (r1:Any, arg1:List[z3.ArithExpr], expr1:z3.BoolExpr) = getLength(m, bad1);
      
          if(r1 == z3.Status.UNSATISFIABLE){
            // Can we work without the need to substitute?
            var xstemp = xs.slice(0, xs.size/2);
            var p1 = expr1;
            for (i <- 0 until xs.size/2){
              p1 = p1.substitute(arg1(i), xstemp(i)).asInstanceOf[z3.BoolExpr];
            }
            xstemp = xs.slice(xs.size/2, xs.size);
            var p2 = expr1;
            for (i <- 0 until xs.size/2){
              p2 = p2.substitute(arg1(i), xstemp(i)).asInstanceOf[z3.BoolExpr];
            }
            solver.add(p1);
            solver.add(p2);
            break;
          }

          var (r2:Any, arg2:List[z3.ArithExpr], expr2:z3.BoolExpr) = checkLength(m, msc, bad2, arg1);

          if(r2 == z3.Status.UNSATISFIABLE){
            // Can we work without the need to substitute?
            var xstemp = xs.slice(0, xs.size/2);
            var p1 = expr2;
            for (i <- 0 until xs.size/2){
              p1 = p1.substitute(arg2(i), xstemp(i)).asInstanceOf[z3.BoolExpr];
            }
            xstemp = xs.slice(xs.size/2, xs.size);
            var p2 = expr2;
            for (i <- 0 until xs.size/2){
              p2 = p2.substitute(arg2(i), xstemp(i)).asInstanceOf[z3.BoolExpr];
            }
            solver.add(p1);
            solver.add(p2);
            break;
          }
        }
        println("UNSAFE");
        break;
      }
      println("SAFE");
      break;
    }
  }

  def getLength(m:TransitionSystem, bad:List[z3.ArithExpr]=>List[z3.BoolExpr]):Tuple3[Any, List[z3.ArithExpr], z3.BoolExpr] = {

    z3.Global.setParameter("fixedpoint.engine", "pdr");
    val cfg = new HashMap[String, String]();
    cfg.put("model", "true");
    val ctx = new z3.Context(cfg);
    val fp = ctx.mkFixedpoint();

    var mp = new TransitionSystem("prime");
    var xs = m.variables;
    var xsp = mp.variables;
    var xst = m.transition(xs);
    val range = 0 until xs.size toList;
    var trx = range.map(i=>ctx.mkEq(xsp(i), xst(i)));
    val sorts = m.sorts.asInstanceOf[Array[z3.Sort]];
    val inv = ctx.mkFuncDecl("inv", sorts, ctx.mkBoolSort());
    val err = ctx.mkFuncDecl("err", Array[z3.Sort](), ctx.mkBoolSort());
    var symbols = new Array[z3.Symbol](xs.size)
    for(i<- 0 until xs.size){
      symbols(i) = ctx.mkSymbol(i).asInstanceOf[z3.Symbol];
    }
    symbols = symbols

    fp.registerRelation(inv);
    fp.registerRelation(err);
    // for(x<-xs:::xsp){
    //   fp.declareVar(x);
    // }

    var qId = 0;
    var skId = 0;

    def createForAll(sorts:Array[z3.Sort], symbols:Array[z3.Symbol], e:z3.Expr):z3.BoolExpr = {
      qId +=1;
      skId +=1;
      ctx.mkForall(sorts, symbols, e, 0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }

    val initCond = ctx.mkAnd(m.initialize(xs):_*);
    val invxs = inv.apply(xs:_*).asInstanceOf[z3.BoolExpr];
    var initRule = ctx.mkImplies(initCond, invxs);
    initRule = createForAll(sorts, symbols, initRule);

    val trxInv = ctx.mkAnd(ctx.mkAnd(trx:_*), invxs);
    val trxAfter = inv.apply(xsp:_*).asInstanceOf[z3.BoolExpr];
    var trxRule = ctx.mkImplies(trxInv, trxAfter);
    trxRule = createForAll(sorts, symbols, trxRule);

    val badxs = ctx.mkAnd(bad(xs):_*);
    val badInv = ctx.mkAnd(badxs, invxs);
    var badRule = ctx.mkImplies(badInv, err.apply().asInstanceOf[z3.BoolExpr]);
    badRule = createForAll(sorts, symbols, badRule);

    fp.addRule(initRule, ctx.mkSymbol("initRule"));
    fp.addRule(trxRule, ctx.mkSymbol("trxRule"));
    fp.addRule(badRule, ctx.mkSymbol("badRule"));

    val rfp = fp.query(Array(err));
    println(rfp);

    return (z3.Status.SATISFIABLE, m.variables, true.asInstanceOf[z3.BoolExpr])
  }

  def checkLength(m:TransitionSystem, msc:SelfComposedTransitionSystem, bad:List[z3.ArithExpr]=>List[z3.BoolExpr], arg:List[z3.ArithExpr]):Tuple3[Any, List[z3.ArithExpr], z3.BoolExpr] = {

    val cfg = new HashMap[String, String]();
    cfg.put("model", "true");
    cfg.put("proof", "true");
    val ctx = z3.InterpolationContext.mkContext(cfg);
    var count = arg.size;
    var x = List(m.addsuffix("0"));
    for(i<-1 until count){
      x = x ::: List(m.addsuffix(i.toString()))
    }

    var badfinal = ctx.mkAnd(bad(x.reverse(0)):_*).simplify().asInstanceOf[z3.BoolExpr]
    var init = ctx.mkAnd(m.initialize(x(0)):_*).simplify().asInstanceOf[z3.BoolExpr]
    var trx = true.asInstanceOf[z3.BoolExpr]
    val range = 0 until x(0).size toList;
    var temp = m.transition(x(0));
    var temp1 = true.asInstanceOf[z3.BoolExpr]
    for(i<-0 until count-1){
      temp = m.transition(x(i));
      temp1 = ctx.mkAnd((range.map(j=>ctx.mkEq(temp(j), x(i+1)(j)))):_*).asInstanceOf[z3.BoolExpr];
      trx = ctx.mkAnd(temp1, trx).asInstanceOf[z3.BoolExpr];
    }

    var s = ctx.mkSolver();
    s.add(init);
    s.add(trx);
    s.add(badfinal);
    var rbmc = s.check();

    if(rbmc == z3.Status.UNSATISFIABLE){
      var formula1 = true.asInstanceOf[z3.BoolExpr];
      var formula2 = true.asInstanceOf[z3.BoolExpr];
      var xprime = List(m.addsuffix("0"));
      for(i<-1 until count){
        xprime = xprime ::: List(m.addsuffix(i.toString()))
      }
      formula1 = ctx.mkAnd(init, trx).asInstanceOf[z3.BoolExpr];
      var initprime = ctx.mkAnd(m.initialize(xprime(0)):_*).simplify().asInstanceOf[z3.BoolExpr]
      var trxprime = true.asInstanceOf[z3.BoolExpr]
      temp = m.transition(xprime(0));
      temp1 = true.asInstanceOf[z3.BoolExpr]
      for(i<-0 until count-1){
        temp = m.transition(xprime(i));
        temp1 = ctx.mkAnd((range.map(j=>ctx.mkEq(temp(j), xprime(i+1)(j)))):_*).asInstanceOf[z3.BoolExpr];
        trxprime = ctx.mkAnd(temp1, trxprime).asInstanceOf[z3.BoolExpr];
      }
      formula1 = ctx.mkAnd(initprime, trxprime).asInstanceOf[z3.BoolExpr]
      // xval are the exact values that consecutive states of M take
      var xval = List(List(ctx.mkInt(0), ctx.mkInt(1)));
      for(i<-0 until count-1){
        xval = xval ::: List(m.transition(xval(i))).asInstanceOf[List[List[z3.IntNum]]]
      }
      for(i<-1 until count){
        formula1 = ctx.mkAnd(formula1, ctx.mkAnd((range.map(j=>ctx.mkEq(x(i)(j), xval(i)(j)))):_*).asInstanceOf[z3.BoolExpr]).asInstanceOf[z3.BoolExpr]
      }
      formula2 = ctx.mkAnd(msc.bad_sc(x(count-1):::xprime(count-1)):_*).simplify().asInstanceOf[z3.BoolExpr];
      return (z3.Status.UNSATISFIABLE, x(count-1):::xprime(count-1), ctx.ComputeInterpolant(ctx.mkAnd(formula1, formula2), ctx.mkParams()).interp(0).asInstanceOf[z3.BoolExpr])
    }

    return (z3.Status.SATISFIABLE, m.variables, true.asInstanceOf[z3.BoolExpr])
  }
}