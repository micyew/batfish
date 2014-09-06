package batfish.representation.cisco;

import batfish.representation.Ip;

public class IpBgpPeerGroup extends BgpPeerGroup {

   private static final long serialVersionUID = 1L;
   private Ip _ip;
   private String _groupName = null;
   private String _peerTemplateName = null;  
   
   public IpBgpPeerGroup(Ip ip) {
      _ip = ip;
   }

   public Ip getIp() {
      return _ip;
   }

   public String getGroupName() {
      return _groupName;
   }

   public String getPeerTemplateName() {
      return _peerTemplateName;
   }

   @Override
   public String getName() {
      return _ip.toString();
   }

   public void setGroupName(String name) throws Exception {
      if (_peerTemplateName != null)
      {
         throw new IllegalArgumentException("Peer Template name has been set.");
      }
      _groupName = name;
   }

   public void setPeerTemplateName(String name) throws Exception {
      if (_groupName != null)
      {
         throw new IllegalArgumentException("Group name has been set.");
      }
      _peerTemplateName = name;
   }
}
