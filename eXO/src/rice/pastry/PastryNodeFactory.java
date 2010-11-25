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

package rice.pastry;

import rice.Continuation;
import rice.environment.Environment;
import rice.environment.logging.Logger;
import rice.p2p.commonapi.CancellableTask;
import rice.pastry.leafset.*;
import rice.pastry.routing.*;

import java.io.*;
import java.util.*;

/**
 * The interface to an object which can construct PastryNodes.
 *
 * @version $Id$
 *
 * @author Andrew Ladd
 * @author Alan Mislove
 * @author Merziyah Poonawala
 * @author Abhishek Ray
 */
public abstract class PastryNodeFactory {

  
  // max number of handles stored per routing table entry
  protected final byte rtMax;

  // leafset size
  protected final byte lSetSize;

  protected final byte rtBase;
  

  /**
   * Hashtable which keeps track of temporary ping values, which are
   * only used during the getNearest() method
   */
  protected Hashtable<NodeHandle, Hashtable<NodeHandle,Integer>> pingCache = new Hashtable<NodeHandle, Hashtable<NodeHandle,Integer>>();

  protected Environment environment;
  
  protected Logger logger;
  
  public PastryNodeFactory(Environment env) {
    this.environment = env;
    rtMax = (byte)environment.getParameters().getInt("pastry_rtMax");
    rtBase = (byte)environment.getParameters().getInt("pastry_rtBaseBitLength");
    lSetSize = (byte)environment.getParameters().getInt("pastry_lSetSize");
    logger = env.getLogManager().getLogger(getClass(), null);
  }
  
  /**
   * Call this to construct a new node of the type chosen by the factory.
   *
   * @param bootstrap The node handle to bootstrap off of
   */
  public abstract PastryNode newNode(NodeHandle bootstrap);

  /**
   * Call this to construct a new node of the type chosen by the factory, with
   * the given nodeId.
   *
   * @param bootstrap The node handle to bootstrap off of
   * @param nodeId The nodeId of the new node
   */
  public abstract PastryNode newNode(NodeHandle bootstrap, Id nodeId);

  /**
   * This method returns the remote leafset of the provided handle
   * to the caller, in a protocol-dependent fashion.  Note that this method
   * may block while sending the message across the wire.
   *
   * @param handle The node to connect to
   * @return The leafset of the remote node
   */
  public abstract LeafSet getLeafSet(NodeHandle handle) throws IOException;
  
  /**
   * Non-blocking version.
   * 
   * @param handle
   * @param c
   * @return
   * @throws IOException
   */
  public abstract CancellableTask getLeafSet(NodeHandle handle, Continuation c);

  /**
   * This method returns the remote route row of the provided handle
   * to the caller, in a protocol-dependent fashion.  Note that this method
   * may block while sending the message across the wire.
   *
   * @param handle The node to connect to
   * @param row The row number to retrieve
   * @return The route row of the remote node
   */
  public abstract RouteSet[] getRouteRow(NodeHandle handle, int row) throws IOException;
  
  /**
   * Non-blocking version.
   * 
   * @param handle
   * @param row
   * @param c
   * @return
   * @throws IOException
   */
  public abstract CancellableTask getRouteRow(NodeHandle handle, int row, Continuation c);

  /**
   * This method determines and returns the proximity of the current local
   * node the provided NodeHandle.  This will need to be done in a protocol-
   * dependent fashion and may need to be done in a special way.
   *
   * @param handle The handle to determine the proximity of
   * @return The proximity of the provided handle
   */
  public abstract int getProximity(NodeHandle local, NodeHandle handle);

  
  
  public Environment getEnvironment() {
    return environment;  
  }
  
  /**
   * Method which checks to see if we have a cached value of the remote ping, and
   * if not, initiates a ping and then caches the value
   *
   * @param handle The handle to ping
   * @return The proximity of the handle
   */
  protected int proximity(NodeHandle local, NodeHandle handle) {
    Hashtable<NodeHandle,Integer> localTable = pingCache.get(local);
    
    if (localTable == null) {
      localTable = new Hashtable();
      pingCache.put(local, localTable);
    }
    
    if (localTable.get(handle) == null) {
      int value = getProximity(local, handle);
      localTable.put(handle, value);

      return value;
    } else {
      return ((Integer) localTable.get(handle)).intValue();
    }
  }
  
  private void purgeProximityCache(NodeHandle local) {
    pingCache.remove(local); 
  }
  
  public NodeHandle[] sortedProximityCache(NodeHandle local) {
    final Hashtable<NodeHandle,Integer> localTable = pingCache.get(local);
    if (localTable == null) return null;
    
    localTable.remove(local); 
    ArrayList<NodeHandle> handles = new ArrayList<NodeHandle>(localTable.keySet());
    Collections.sort(handles,new Comparator<NodeHandle>() {
    
      public int compare(NodeHandle a, NodeHandle b) {
        return localTable.get(a).intValue()-localTable.get(b).intValue();
      }    
    });
    
//    Iterator<NodeHandle> i = handles.iterator();
//    while(i.hasNext()) {
//      NodeHandle nh = i.next();
//      System.out.println(nh+":"+localTable.get(nh));
//    }
    
    return (NodeHandle[])handles.toArray(new NodeHandle[0]);
  }
  
  /**
   * This method implements the algorithm in the Pastry locality paper
   * for finding a close node the the current node through iterative
   * leafset and route row requests.  The seed node provided is any
   * node in the network which is a member of the pastry ring.  This
   * algorithm is designed to work in a protocol-independent manner, using
   * the getResponse(Message) method provided by subclasses.
   *
   * @param seed Any member of the pastry ring
   * @return A node suitable to boot off of (which is close the this node)
   */
  public NodeHandle[] getNearest(NodeHandle local, NodeHandle seed) {
    try {
      // if the seed is null, we can't do anything
      if (seed == null)
        return null;
      
      // seed is the bootstrap node that we use to enter the pastry ring
      NodeHandle currentClosest = seed;
      NodeHandle nearNode = seed;
      
      // get closest node in leafset
      nearNode = closestToMe(local, nearNode, getLeafSet(nearNode));
      
      // get the number of rows in a routing table
      // -- Here, we're going to be a little inefficient now.  It doesn't
      // -- impact correctness, but we're going to walk up from the bottom
      // -- of the routing table, even through some of the rows are probably
      // -- unfilled.  We'll optimize this in a later iteration.
      int depth = (Id.IdBitLength / rtBase);
      int i = 0;

      
      
      // make "ALL" work
      if (!environment.getParameters().getString("pns_num_rows_to_use").
          equalsIgnoreCase("all")) {
        i = depth-environment.getParameters().getInt("pns_num_rows_to_use");
      }
      
      // fix it up to not throw an error if the number is too big
      if (i < 0) i = 0;
      
      // now, iteratively walk up the routing table, picking the closest node
      // each time for the next request
      while (i < depth) {
        nearNode = closestToMe(local, nearNode, getRouteRow(nearNode, i));
        i++;
      }
      
      // finally, recursively examine the top level routing row of the nodes
      // until no more progress can be made
      do {
        currentClosest = nearNode;
        nearNode = closestToMe(local, nearNode, getRouteRow(nearNode, depth-1));
      } while (! currentClosest.equals(nearNode));
      
      if (nearNode.getLocalNode() == null) {
        // this is messy, but here's the deal:
        // when this is called by user code, local will have a localNode
        // when SPNF calls it, the node may not, because I needed to move
        // getNearest() to before the creation of the pastry node so that it
        // uses the same port, this is necessary so that the firewall settings
        // will work, otherwies, getNearest() used to have to bind to the next port
        // because the pastry node was already bound to its port, now, we do 
        // getNearest() first so we can keep the port the same
        if (local.getLocalNode() != null)
          nearNode = local.getLocalNode().coalesce(nearNode);
      }
      
      // return the resulting closest node
//      return nearNode;
      return sortedProximityCache(local);
    } catch (IOException e) {
      if (logger.level <= Logger.WARNING) logger.logException(
        "ERROR occured while finding best bootstrap.", e);
      return new NodeHandle[]{seed};
    } finally {
      purgeProximityCache(local); 
    }
  }

  /**
   * This method returns the closest node to the current node out of
   * the union of the provided handle and the node handles in the
   * leafset
   *
   * @param handle The handle to include
   * @param leafSet The leafset to include
   * @return The closest node out of handle union leafset
   */
  private NodeHandle closestToMe(NodeHandle local, NodeHandle handle, LeafSet leafSet)  {
    Vector handles = new Vector();

    for (int i = 1; i <= leafSet.cwSize() ; i++)
      handles.add(leafSet.get(i));

    for (int i = -leafSet.ccwSize(); i < 0; i++)
      handles.add(leafSet.get(i));

    return closestToMe(local, handle, (NodeHandle[]) handles.toArray(new NodeHandle[0]));
  }

  /**
   * This method returns the closest node to the current node out of
   * the union of the provided handle and the node handles in the
   * routeset
   *
   * @param handle The handle to include
   * @param routeSet The routeset to include
   * @return The closest node out of handle union routeset
   */
  private NodeHandle closestToMe(NodeHandle local, NodeHandle handle, RouteSet[] routeSets) {
    Vector handles = new Vector();

    for (int i=0 ; i<routeSets.length ; i++) {
      RouteSet set = routeSets[i];

      if (set != null) {
        for (int j=0; j<set.size(); j++)
          handles.add(set.get(j));
      }
    }

    return closestToMe(local, handle, (NodeHandle[]) handles.toArray(new NodeHandle[0]));
  }

  /**
   * This method returns the closest node to the current node out of
   * the union of the provided handle and the node handles in the
   * array
   *
   * @param handle The handle to include
   * @param handles The array to include
   * @return The closest node out of handle union array
   */
  private NodeHandle closestToMe(NodeHandle local, NodeHandle handle, NodeHandle[] handles) {
    NodeHandle closestNode = handle;

    // shortest distance found till now    
    int nearestdist = proximity(local, closestNode);  

    for (int i=0; i < handles.length; i++) {
      NodeHandle tempNode = handles[i];

      int prox = proximity(local, tempNode);
      
      if ((prox > 0) && (prox < nearestdist) && tempNode.isAlive()) {
        nearestdist = prox;
        closestNode = tempNode;
      }
    }
    
    return closestNode;
  }
}
