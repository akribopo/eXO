/*******************************************************************************

"FreePastry" Peer-to-Peer Application Development Substrate

Copyright 2002-2007, Rice University. Copyright 2006-2007, Max Planck Institute 
for Software Systems.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of Rice  University (RICE), Max Planck Institute for Software 
Systems (MPI-SWS) nor the names of its contributors may be used to endorse or 
promote products derived from this software without specific prior written 
permission.

This software is provided by RICE, MPI-SWS and the contributors on an "as is" 
basis, without any representations or warranties of any kind, express or implied 
including, but not limited to, representations or warranties of 
non-infringement, merchantability or fitness for a particular purpose. In no 
event shall RICE, MPI-SWS or contributors be liable for any direct, indirect, 
incidental, special, exemplary, or consequential damages (including, but not 
limited to, procurement of substitute goods or services; loss of use, data, or 
profits; or business interruption) however caused and on any theory of 
liability, whether in contract, strict liability, or tort (including negligence
or otherwise) arising in any way out of the use of this software, even if 
advised of the possibility of such damage.

*******************************************************************************/ 

package rice.pastry.direct;

import rice.Continuation;
import rice.environment.Environment;
import rice.environment.logging.*;
import rice.p2p.commonapi.CancellableTask;
import rice.pastry.*;
import rice.pastry.messaging.*;
import rice.pastry.standard.*;
import rice.pastry.routing.*;
import rice.pastry.leafset.*;

import java.io.*;
import java.util.*;

/**
 * Pastry node factory for direct connections between nodes (local instances).
 *
 * @version $Id$
 *
 * @author Andrew Ladd
 * @author Sitaram Iyer
 * @author Rongmei Zhang/Y. Charlie Hu
 */
public class DirectPastryNodeFactory extends PastryNodeFactory {

  private NodeIdFactory nidFactory;
  private NetworkSimulator simulator;

  boolean guaranteeConsistency;
  

  
  /**
   * Main constructor.
   * 
   * @param nf the NodeIdFactory
   * @param sim the NetworkSimulator
   * @param e the Enviornment
   */
  public DirectPastryNodeFactory(NodeIdFactory nf, NetworkSimulator sim, Environment env) {    
    super(env);
    env.getParameters().setInt("pastry_protocol_consistentJoin_max_time_to_be_scheduled",120000);
    guaranteeConsistency = env.getParameters().getBoolean("pastry_direct_guarantee_consistency"); // true
    nidFactory = nf;
    simulator = sim;
  }

  /**
   * Getter for the NetworkSimulator.
   * 
   * @return the NetworkSimulator we are using.
   */
  public NetworkSimulator getNetworkSimulator() { return simulator; }
  
  /**
   * Manufacture a new Pastry node.
   *
   * @return a new PastryNode
   */
  public PastryNode newNode(NodeHandle bootstrap) {
    return newNode(bootstrap, nidFactory.generateNodeId());
  }

  Hashtable recordTable = new Hashtable();
  
  /**
   * Manufacture a new Pastry node.
   *
   * @return a new PastryNode
   */
  public PastryNode newNode(NodeHandle bootstrap, Id nodeId) {
    if (bootstrap == null)
      if (logger.level <= Logger.WARNING) logger.log(
          "No bootstrap node provided, starting a new ring...");
    
    // this code builds a different environment for each PastryNode
    Environment environment = this.environment;
    if (this.environment.getParameters().getBoolean("pastry_factory_multipleNodes")) {
      if (this.environment.getLogManager() instanceof CloneableLogManager) {
        environment = new Environment(
            this.environment.getSelectorManager(),
            this.environment.getProcessor(),
          this.environment.getRandomSource(),
          this.environment.getTimeSource(),
          ((CloneableLogManager)this.environment.getLogManager()).clone("0x"+nodeId.toStringBare()),
          this.environment.getParameters(), 
          this.environment.getExceptionStrategy());
      }
    }    

    NodeRecord nr = (NodeRecord)recordTable.get(nodeId);
    if (nr == null) {
      nr = simulator.generateNodeRecord();
      recordTable.put(nodeId,nr);
    }
    DirectPastryNode pn = new DirectPastryNode(nodeId, simulator, environment, nr);

    DirectNodeHandle localhandle = new DirectNodeHandle(pn, pn, simulator);
    simulator.registerNode(pn);

    MessageDispatch msgDisp = new MessageDispatch(pn);
 
    RoutingTable routeTable = new RoutingTable(localhandle, rtMax, rtBase, pn);
    LeafSet leafSet = new LeafSet(localhandle, lSetSize, routeTable);

    StandardRouter router =
      new StandardRouter(pn);
    StandardRouteSetProtocol rsProtocol =
      new StandardRouteSetProtocol(pn, routeTable, environment);

    pn.setElements(localhandle, msgDisp, leafSet, routeTable);
    router.register();
    rsProtocol.register();

    if (guaranteeConsistency) {    
        PeriodicLeafSetProtocol lsProtocol = new PeriodicLeafSetProtocol(pn,
            localhandle, leafSet, routeTable);
        lsProtocol.register();
        ConsistentJoinProtocol jProtocol = new ConsistentJoinProtocol(pn,
            localhandle, routeTable, leafSet, lsProtocol);
        jProtocol.register();
    } else {
      StandardLeafSetProtocol lsProtocol = new StandardLeafSetProtocol(pn,
          localhandle, leafSet, routeTable);
      lsProtocol.register();
      StandardJoinProtocol jProtocol = new StandardJoinProtocol(pn,
          localhandle, routeTable, leafSet);
      jProtocol.register();      
    }
    
    // pn.doneNode(bootstrap);
    //pn.doneNode( simulator.getClosest(localhandle) );    
    pn.doneNode(getNearest(localhandle, bootstrap));
      
    return pn;
  }

  /**
   * This method returns the remote leafset of the provided handle
   * to the caller, in a protocol-dependent fashion.  Note that this method
   * may block while sending the message across the wire.
   *
   * @param handle The node to connect to
   * @return The leafset of the remote node
   */
  public LeafSet getLeafSet(NodeHandle handle) throws IOException {
    DirectNodeHandle dHandle = (DirectNodeHandle) handle;

    return dHandle.getRemote().getLeafSet();
  }

  /**
   * The non-blocking versions here all execute immeadiately.  
   * This CancellableTask is just a placeholder.
   * @author Jeff Hoye
   */
  static class NullCancellableTask implements CancellableTask {
    public void run() {
    }

    public boolean cancel() {
      return false;
    }

    public long scheduledExecutionTime() {
      return 0;
    }
  }

  
  public CancellableTask getLeafSet(NodeHandle handle, Continuation c) {
    DirectNodeHandle dHandle = (DirectNodeHandle) handle;
    c.receiveResult(dHandle.getRemote().getLeafSet());
    return new NullCancellableTask();  
  }

  /**
   * This method returns the remote route row of the provided handle
   * to the caller, in a protocol-dependent fashion.  Note that this method
   * may block while sending the message across the wire.
   *
   * @param handle The node to connect to
   * @param row The row number to retrieve
   * @return The route row of the remote node
   */
  public RouteSet[] getRouteRow(NodeHandle handle, int row) throws IOException {
    DirectNodeHandle dHandle = (DirectNodeHandle) handle;

    return dHandle.getRemote().getRoutingTable().getRow(row);
  }

  public CancellableTask getRouteRow(NodeHandle handle, int row, Continuation c) {
    DirectNodeHandle dHandle = (DirectNodeHandle) handle;
    c.receiveResult(dHandle.getRemote().getRoutingTable().getRow(row));
    return new NullCancellableTask();  
  }
  
  /**
   * This method determines and returns the proximity of the current local
   * node the provided NodeHandle.  This will need to be done in a protocol-
   * dependent fashion and may need to be done in a special way.
   *
   * @param handle The handle to determine the proximity of
   * @return The proximity of the provided handle
   */
  public int getProximity(NodeHandle local, NodeHandle remote) {
    return (int)simulator.proximity((DirectNodeHandle)local, (DirectNodeHandle)remote);
  }

//  protected int proximity(NodeHandle local, NodeHandle handle) {
//    return getProximity(local, handle);
//  }

}
