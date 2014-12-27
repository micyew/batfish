package batfish.representation.juniper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import batfish.collections.RoleSet;
import batfish.representation.BgpNeighbor;
import batfish.representation.BgpProcess;
import batfish.representation.Configuration;
import batfish.representation.Ip;
import batfish.representation.IpAccessList;
import batfish.representation.IpAccessListLine;
import batfish.representation.LineAction;
import batfish.representation.OspfMetricType;
import batfish.representation.OspfProcess;
import batfish.representation.PolicyMap;
import batfish.representation.PolicyMapAction;
import batfish.representation.PolicyMapClause;
import batfish.representation.PolicyMapMatchLine;
import batfish.representation.PolicyMapMatchRouteFilterListLine;
import batfish.representation.RouteFilterLengthRangeLine;
import batfish.representation.RouteFilterList;
import batfish.representation.VendorConfiguration;
import batfish.representation.VendorConversionException;
import batfish.representation.juniper.BgpGroup.BgpGroupType;
import batfish.util.SubRange;
import batfish.util.Util;

public final class JuniperVendorConfiguration extends JuniperConfiguration
      implements VendorConfiguration {

   private static final long serialVersionUID = 1L;

   private static final String VENDOR_NAME = "juniper";

   private Configuration _c;

   private final List<String> _conversionWarnings;

   private final RoleSet _roles;

   public JuniperVendorConfiguration() {
      _conversionWarnings = new ArrayList<String>();
      _roles = new RoleSet();
   }

   private BgpProcess createBgpProcess() {
      BgpProcess proc = new BgpProcess();
      BgpGroup mg = _defaultRoutingInstance.getMasterBgpGroup();
      if (mg.getLocalAs() == null) {
         mg.setLocalAs(_defaultRoutingInstance.getAs());
      }
      for (Entry<Ip, IpBgpGroup> e : _defaultRoutingInstance.getIpBgpGroups()
            .entrySet()) {
         Ip ip = e.getKey();
         IpBgpGroup ig = e.getValue();
         ig.cascadeInheritance();
         BgpNeighbor neighbor = new BgpNeighbor(ip);
         // import policies
         for (String importPolicyName : ig.getExportPolicies()) {
            PolicyMap importPolicy = _c.getPolicyMaps().get(importPolicyName);
            if (importPolicy == null) {
               throw new VendorConversionException(
                     "missing bgp import policy: \"" + importPolicyName + "\"");
            }
            neighbor.addInboundPolicyMap(importPolicy);
         }
         // export policies
         for (String exportPolicyName : ig.getExportPolicies()) {
            PolicyMap exportPolicy = _c.getPolicyMaps().get(exportPolicyName);
            if (exportPolicy == null) {
               throw new VendorConversionException(
                     "missing bgp export policy: \"" + exportPolicyName + "\"");
            }
            neighbor.addOutboundPolicyMap(exportPolicy);
         }
         // inherit local-as
         neighbor.setLocalAs(ig.getLocalAs());

         // inherit peer-as, or use local-as if internal
         if (ig.getType() == BgpGroupType.INTERNAL) {
            neighbor.setRemoteAs(ig.getLocalAs());
         }
         else {
            neighbor.setRemoteAs(ig.getPeerAs());
         }

         // TODO: implement better behavior than setting default metric to 0
         neighbor.setDefaultMetric(0);

         // TODO: find out if there is a juniper equivalent of cisco
         // send-community
         neighbor.setSendCommunity(true);

         proc.getNeighbors().put(ip, neighbor);
      }
      return proc;
   }

   private OspfProcess createOspfProcess() {
      OspfProcess newProc = new OspfProcess();
      // export policies
      for (String exportPolicyName : _defaultRoutingInstance
            .getOspfExportPolicies()) {
         PolicyMap exportPolicy = _c.getPolicyMaps().get(exportPolicyName);
         newProc.getOutboundPolicyMaps().add(exportPolicy);
         // TODO: support type E1
         newProc.getPolicyMetricTypes().put(exportPolicy, OspfMetricType.E2);
      }
      // areas
      Map<Long, batfish.representation.OspfArea> newAreas = newProc.getAreas();
      for (Entry<Ip, OspfArea> e : _defaultRoutingInstance.getOspfAreas()
            .entrySet()) {
         Ip areaIp = e.getKey();
         long areaLong = areaIp.asLong();
         // OspfArea area = e.getValue();
         batfish.representation.OspfArea newArea = new batfish.representation.OspfArea(
               areaLong);
         newAreas.put(areaLong, newArea);
      }
      // place interfaces into areas
      for (Entry<String, Interface> e : _defaultRoutingInstance.getInterfaces()
            .entrySet()) {
         String name = e.getKey();
         Interface iface = e.getValue();
         placeInterfaceIntoArea(newAreas, name, iface);
         for (Entry<String, Interface> eUnit : iface.getUnits().entrySet()) {
            String unitName = eUnit.getKey();
            Interface unitIface = eUnit.getValue();
            placeInterfaceIntoArea(newAreas, unitName, unitIface);
         }
      }
      newProc.setRouterId(_defaultRoutingInstance.getRouterId());
      newProc.setReferenceBandwidth(_defaultRoutingInstance
            .getOspfReferenceBandwidth());
      return newProc;
   }

   @Override
   public List<String> getConversionWarnings() {
      return _conversionWarnings;
   }

   @Override
   public RoleSet getRoles() {
      return _roles;
   }

   private void placeInterfaceIntoArea(
         Map<Long, batfish.representation.OspfArea> newAreas, String name,
         Interface iface) {
      batfish.representation.Interface newIface = _c.getInterfaces().get(name);
      Ip ospfArea = iface.getOspfArea();
      if (ospfArea != null) {
         long ospfAreaLong = ospfArea.asLong();
         batfish.representation.OspfArea newArea = newAreas.get(ospfAreaLong);
         newArea.getInterfaces().add(newIface);
      }
   }

   @Override
   public void setRoles(RoleSet roles) {
      _roles.addAll(roles);
   }

   private batfish.representation.GeneratedRoute toAggregateRoute(
         AggregateRoute route) {
      Ip prefix = route.getPrefix().getAddress();
      int prefixLength = route.getPrefix().getPrefixLength();
      int administrativeCost = route.getMetric();
      String policyNameSuffix = route.getPrefix().toString().replace('/', '_')
            .replace('.', '_');
      String policyName = "~AGGREGATE_" + policyNameSuffix + "~";
      PolicyMap policy = new PolicyMap(policyName);
      PolicyMapClause clause = new PolicyMapClause();
      policy.getClauses().add(clause);
      clause.setAction(PolicyMapAction.PERMIT);
      String rflName = "~AGGREGATE_" + policyNameSuffix + "_RF~";
      RouteFilterList rfList = new RouteFilterList(rflName);
      rfList.addLine(new RouteFilterLengthRangeLine(LineAction.ACCEPT, prefix,
            prefixLength, new SubRange(prefixLength + 1, 32)));
      PolicyMapMatchLine matchLine = new PolicyMapMatchRouteFilterListLine(
            Collections.singleton(rfList));
      clause.getMatchLines().add(matchLine);
      Set<PolicyMap> policies = Collections.singleton(policy);
      batfish.representation.GeneratedRoute newRoute = new batfish.representation.GeneratedRoute(
            prefix, prefixLength, administrativeCost, policies);
      _c.getPolicyMaps().put(policyName, policy);
      _c.getRouteFilterLists().put(rflName, rfList);
      return newRoute;
   }

   private batfish.representation.CommunityList toCommunityList(CommunityList cl) {
      String name = cl.getName();
      List<batfish.representation.CommunityListLine> newLines = new ArrayList<batfish.representation.CommunityListLine>();
      for (CommunityListLine line : cl.getLines()) {
         batfish.representation.CommunityListLine newLine = new batfish.representation.CommunityListLine(
               LineAction.ACCEPT, line.getRegex());
         newLines.add(newLine);
      }
      batfish.representation.CommunityList newCl = new batfish.representation.CommunityList(
            name, newLines);
      return newCl;
   }

   private batfish.representation.GeneratedRoute toGeneratedRoute(
         GeneratedRoute route) {
      Ip prefix = route.getPrefix().getAddress();
      int prefixLength = route.getPrefix().getPrefixLength();
      int administrativeCost = route.getMetric();
      Set<PolicyMap> policies = new LinkedHashSet<PolicyMap>();
      for (String policyName : route.getPolicies()) {
         PolicyMap policy = _c.getPolicyMaps().get(policyName);
         if (policy == null) {
            throw new VendorConversionException(
                  "missing generated route policy: \"" + policyName + "\"");
         }
         policies.add(policy);
      }
      batfish.representation.GeneratedRoute newRoute = new batfish.representation.GeneratedRoute(
            prefix, prefixLength, administrativeCost, policies);
      return newRoute;
   }

   private batfish.representation.Interface toInterface(Interface iface) {
      String name = iface.getName();
      batfish.representation.Interface newIface = new batfish.representation.Interface(
            name);
      String inAclName = iface.getIncomingFilter();
      if (inAclName != null) {
         IpAccessList inAcl = _c.getIpAccessLists().get(inAclName);
         if (inAcl == null) {
            throw new VendorConversionException("missing incoming acl: \""
                  + inAclName + "\"");
         }
         newIface.setIncomingFilter(inAcl);
      }
      String outAclName = iface.getOutgoingFilter();
      if (outAclName != null) {
         IpAccessList outAcl = _c.getIpAccessLists().get(outAclName);
         if (outAcl == null) {
            throw new VendorConversionException("missing outgoing acl: \""
                  + outAclName + "\"");
         }
         newIface.setOutgoingFilter(outAcl);
      }
      if (iface.getPrefix() != null) {
         newIface.setIp(iface.getPrefix().getAddress());
         newIface.setSubnetMask(iface.getPrefix().getSubnetMask());
      }
      newIface.setActive(iface.getActive());
      newIface.setAccessVlan(iface.getAccessVlan());
      newIface.setNativeVlan(iface.getNativeVlan());
      newIface.setSwitchportMode(iface.getSwitchportMode());
      newIface.setSwitchportTrunkEncapsulation(iface
            .getSwitchportTrunkEncapsulation());
      newIface.setBandwidth(iface.getBandwidth());
      return newIface;
   }

   private IpAccessList toIpAccessList(FirewallFilter filter)
         throws VendorConversionException {
      String name = filter.getName();
      List<IpAccessListLine> lines = new ArrayList<IpAccessListLine>();
      for (FwTerm term : filter.getTerms().values()) {
         // action
         LineAction action;
         if (term.getThens().contains(FwThenAccept.INSTANCE)) {
            action = LineAction.ACCEPT;
         }
         else if (term.getThens().contains(FwThenDiscard.INSTANCE)) {
            action = LineAction.REJECT;
         }
         else if (term.getThens().contains(FwThenNextTerm.INSTANCE)) {
            // TODO: throw error if any transformation is being done
            continue;
         }
         else {
            throw new VendorConversionException(
                  "not sure what to do without corresponding access list action from firewall filter term");
         }
         IpAccessListLine line = new IpAccessListLine();
         line.setAction(action);
         for (FwFrom from : term.getFroms()) {
            from.applyTo(line);
         }
         lines.add(line);
      }
      IpAccessList list = new IpAccessList(name, lines);
      return list;
   }

   private PolicyMap toPolicyMap(PolicyStatement ps) {
      String name = ps.getName();
      PolicyMap map = new PolicyMap(name);
      boolean singleton = ps.getSingletonTerm().getFroms().size() > 0
            || ps.getSingletonTerm().getThens().size() > 0;
      Collection<PsTerm> terms = singleton ? Collections.singleton(ps
            .getSingletonTerm()) : ps.getTerms().values();
      for (PsTerm term : terms) {
         PolicyMapClause clause = new PolicyMapClause();
         clause.setName(term.getName());
         for (PsFrom from : term.getFroms()) {
            from.applyTo(clause, _c);
         }
         for (PsThen then : term.getThens()) {
            then.applyTo(clause, _c);
         }
      }
      return map;
   }

   private batfish.representation.StaticRoute toStaticRoute(StaticRoute route) {
      Ip prefix = route.getPrefix().getAddress();
      int prefixLength = route.getPrefix().getPrefixLength();
      Ip nextHopIp = route.getNextHopIp();
      String nextHopInterface = route.getDrop() ? Util.NULL_INTERFACE_NAME
            : route.getNextHopInterface();
      int administrativeCost = route.getMetric();
      Integer tag = route.getTag();
      batfish.representation.StaticRoute newStaticRoute = new batfish.representation.StaticRoute(
            prefix, prefixLength, nextHopIp, nextHopInterface,
            administrativeCost, tag);
      return newStaticRoute;
   }

   @Override
   public Configuration toVendorIndependentConfiguration()
         throws VendorConversionException {
      String hostname = getHostname();
      _c = new Configuration(hostname);
      _c.setVendor(VENDOR_NAME);
      _c.setRoles(_roles);

      // convert firewall filters to ipaccesslists
      for (Entry<String, FirewallFilter> e : _filters.entrySet()) {
         String name = e.getKey();
         FirewallFilter filter = e.getValue();
         // TODO: support other filter families
         if (filter.getFamily() != Family.INET) {
            continue;
         }
         IpAccessList list = toIpAccessList(filter);
         _c.getIpAccessLists().put(name, list);
      }

      // convert route filters to route filter lists
      for (Entry<String, RouteFilter> e : _routeFilters.entrySet()) {
         String name = e.getKey();
         RouteFilter rf = e.getValue();
         RouteFilterList rfl = new RouteFilterList(name);
         for (RouteFilterLine line : rf.getLines()) {
            line.applyTo(rfl);
         }
         _c.getRouteFilterLists().put(name, rfl);
      }

      // convert community lists
      for (Entry<String, CommunityList> e : _communityLists.entrySet()) {
         String name = e.getKey();
         CommunityList cl = e.getValue();
         batfish.representation.CommunityList newCl = toCommunityList(cl);
         _c.getCommunityLists().put(name, newCl);
      }

      // convert policy-statements to policymaps
      for (Entry<String, PolicyStatement> e : _policyStatements.entrySet()) {
         String name = e.getKey();
         PolicyStatement ps = e.getValue();
         PolicyMap map = toPolicyMap(ps);
         _c.getPolicyMaps().put(name, map);
      }

      // convert interfaces
      for (Entry<String, Interface> e : _defaultRoutingInstance.getInterfaces()
            .entrySet()) {
         String name = e.getKey();
         Interface iface = e.getValue();
         batfish.representation.Interface newIface = toInterface(iface);
         _c.getInterfaces().put(name, newIface);
         for (Entry<String, Interface> eUnit : iface.getUnits().entrySet()) {
            String unitName = eUnit.getKey();
            Interface unitIface = eUnit.getValue();
            batfish.representation.Interface newUnitIface = toInterface(unitIface);
            _c.getInterfaces().put(unitName, newUnitIface);
         }
      }

      // static routes
      for (StaticRoute route : _defaultRoutingInstance.getRibs()
            .get(RoutingInformationBase.RIB_IPV4_UNICAST).getStaticRoutes()
            .values()) {
         batfish.representation.StaticRoute newStaticRoute = toStaticRoute(route);
         _c.getStaticRoutes().add(newStaticRoute);
      }

      // aggregate routes
      for (AggregateRoute route : _defaultRoutingInstance.getRibs()
            .get(RoutingInformationBase.RIB_IPV4_UNICAST).getAggregateRoutes()
            .values()) {
         batfish.representation.GeneratedRoute newAggregateRoute = toAggregateRoute(route);
         _c.getGeneratedRoutes().add(newAggregateRoute);
      }

      // generated routes
      for (GeneratedRoute route : _defaultRoutingInstance.getRibs()
            .get(RoutingInformationBase.RIB_IPV4_UNICAST).getGeneratedRoutes()
            .values()) {
         batfish.representation.GeneratedRoute newGeneratedRoute = toGeneratedRoute(route);
         _c.getGeneratedRoutes().add(newGeneratedRoute);
      }

      // create ospf process
      if (_defaultRoutingInstance.getOspfAreas().size() > 0) {
         OspfProcess oproc = createOspfProcess();
         _c.setOspfProcess(oproc);
      }

      // create bgp process
      if (_defaultRoutingInstance.getNamedBgpGroups().size() > 0
            || _defaultRoutingInstance.getIpBgpGroups().size() > 0) {
         BgpProcess proc = createBgpProcess();
         _c.setBgpProcess(proc);
      }

      return _c;
   }
}
