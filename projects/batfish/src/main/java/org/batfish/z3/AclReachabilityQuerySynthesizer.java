package org.batfish.z3;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.Z3Exception;
import java.util.ArrayList;
import java.util.List;
import org.batfish.z3.node.AclMatchExpr;
import org.batfish.z3.node.AndExpr;
import org.batfish.z3.node.DeclareFunExpr;
//import org.batfish.z3.node.DeclareRelExpr;
import org.batfish.z3.node.DeclareRelExpr;
import org.batfish.z3.node.NumberedQueryExpr;
import org.batfish.z3.node.QueryExpr;
import org.batfish.z3.node.RuleExpr;
import org.batfish.z3.node.SaneExpr;

public final class AclReachabilityQuerySynthesizer extends SatQuerySynthesizer<AclLine> {

  private final String _aclName;

  private final String _hostname;

  private final int _numLines;

  private final boolean _useSMT;

  public AclReachabilityQuerySynthesizer(String hostname, String aclName, int numLines) {
    this(hostname,aclName,numLines,false);
  }

  public AclReachabilityQuerySynthesizer(String hostname, String aclName, int numLines, boolean useSMT) {
    _hostname = hostname;
    _aclName = aclName;
    _numLines = numLines;
    _useSMT = useSMT;
  }

  @Override
  public NodProgram getNodProgram(NodProgram baseProgram) throws Z3Exception {
    Context ctx = baseProgram.getContext();
    NodProgram program = new NodProgram(ctx);
    for (int line = 0; line < _numLines; line++) {
      AclMatchExpr matchAclLine = new AclMatchExpr(_hostname, _aclName, line);
      AndExpr queryConditions = new AndExpr();
      queryConditions.addConjunct(matchAclLine);
      queryConditions.addConjunct(SaneExpr.INSTANCE);
      NumberedQueryExpr queryRel = new NumberedQueryExpr(line);
      String queryRelName = queryRel.getRelations().toArray(new String[] {})[0];
      List<Integer> sizes = new ArrayList<>();
      FuncDecl declaration;
      if(_useSMT) {
         declaration = new DeclareFunExpr(queryRelName, sizes).toFuncDecl(ctx);
      } else {
        sizes.addAll(Synthesizer.PACKET_VAR_SIZES.values());
        declaration = new DeclareRelExpr(queryRelName, sizes).toFuncDecl(ctx);
      }
      baseProgram.getRelationDeclarations().put(queryRelName, declaration);
      RuleExpr queryRule = new RuleExpr(queryConditions, queryRel);
      List<BoolExpr> rules = program.getRules();
      rules.add(queryRule.toBoolExpr(baseProgram));
      QueryExpr query = new QueryExpr(queryRel);
      BoolExpr queryBoolExpr = query.toBoolExpr(baseProgram);
      program.getQueries().add(queryBoolExpr);
      _keys.add(new AclLine(_hostname, _aclName, line));
    }
    return program;
  }

  @Override
  public NodProgram synthesizeBaseProgram(Synthesizer synthesizer, Context ctx) {
    return synthesizer.synthesizeNodAclProgram(_hostname, _aclName, ctx);
  }
}
