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


import java.util.Hashtable;

import rice.environment.Environment;
import rice.environment.logging.Logger;
import rice.p2p.commonapi.*;
import rice.p2p.commonapi.appsocket.AppSocketReceiver;
import rice.p2p.commonapi.rawserialization.InputBuffer;
import rice.pastry.*;
import rice.pastry.Id;
import rice.pastry.NodeHandle;
import rice.pastry.client.PastryAppl;
import rice.pastry.join.InitiateJoin;
import rice.pastry.messaging.Message;
import rice.pastry.routing.RouteMessage;
import rice.selector.*;

/**
 * Direct pastry node. Subclasses PastryNode, and does about nothing else.
 * 
 * @version $Id$
 * 
 * @author Sitaram Iyer
 */

public class DirectPastryNode extends PastryNode {
  /**
   * Used for proximity calculation of DirectNodeHandle. This will probably go
   * away when we switch to a byte-level protocol.
   */
  static private Hashtable currentNode = new Hashtable();
  
  /**
   * Returns the previous one.
   * 
   * @param dnh
   * @return
   */
  public static synchronized DirectPastryNode setCurrentNode(DirectPastryNode dpn) {
    Thread current = Thread.currentThread();
    DirectPastryNode ret = (DirectPastryNode)currentNode.get(current);
    if (dpn == null) {
      currentNode.remove(current);
    } else {
      currentNode.put(current, dpn);
    } 
    return ret;
  }
  
  public static synchronized DirectPastryNode getCurrentNode() {
    Thread current = Thread.currentThread();
    DirectPastryNode ret = (DirectPastryNode)currentNode.get(current);
    return ret;    
  }

  
  
  private NetworkSimulator simulator;
  private boolean alive = true;
  NodeRecord record;
  protected Timer timer;
  // used to control message order for messages destined to arrive at the same time
  int seq = 0;

  public DirectPastryNode(Id id, NetworkSimulator sim, Environment e, NodeRecord nr) {
    super(id, e);
    timer = e.getSelectorManager().getTimer();
    simulator = sim;
    record = nr;
  }

  public void doneNode(NodeHandle[] bootstrap) {
    initiateJoin(bootstrap);
  }

  public boolean isAlive() {
    return alive; 
  }
  
  public void destroy() {
    SelectorManager sm = getEnvironment().getSelectorManager();
    if (!sm.isSelectorThread()) {
      sm.invoke(new Runnable() {
      
        public void run() {
          destroy();
        }      
      });
      return;
    }
    record.markDead();
    routeSet.destroy();
    leafSet.destroy();
    
    super.destroy();
    alive = false;
    if (joinTask != null) joinTask.cancel();
    setReadyStrategy(new ReadyStrategy() {
      // destroyed ready strategy
      public void start() {
        throw new RuntimeException("Can't start!");
      }
    
      public boolean isReady() {
        return false;
      }
    
      public void setReady(boolean r) {
      }

      public void stop() {
        throw new RuntimeException("Can't stop!");        
      }    
    });
    setReady(false); 
    notifyReadyObservers();
    simulator.removeNode(this);
  }
  
  
//  public final void initiateJoin(NodeHandle bootstrap) {
//    NodeHandle[] boots = new NodeHandle[1];
//    boots[0] = bootstrap;
//    initiateJoin(boots);
//  }
  
  /**
   * Sends an InitiateJoin message to itself.
   * 
   * @param bootstrap
   *          Node handle to bootstrap with.
   */
  ScheduledMessage joinTask;
  public final void initiateJoin(NodeHandle[] bootstrap) {
    if (bootstrap != null && bootstrap[0] != null) {
      joinTask = scheduleMsg(new InitiateJoin(bootstrap), 0, 5000);
//      this.receiveMessage(new InitiateJoin(bootstrap));
    } else {
      setReady(); // no bootstrap node, so ready immediately
    }
  }

  /**
   * Called from PastryNode after the join succeeds.
   */
  public final void nodeIsReady() {
    if (joinTask != null) {
      joinTask.cancel();
      joinTask = null;
    }
  }

  /**
   * Schedule the specified message to be sent to the local node after a
   * specified delay. Useful to provide timeouts.
   * 
   * @param msg
   *          a message that will be delivered to the local node after the
   *          specified delay
   * @param delay
   *          time in milliseconds before message is to be delivered
   * @return the scheduled event object; can be used to cancel the message
   */
  public ScheduledMessage scheduleMsg(Message msg, long delay) {
    return simulator.deliverMessage(msg, this, (DirectNodeHandle)this.getLocalHandle(), (int)delay);
  }

  /**
   * Schedule the specified message for repeated fixed-delay delivery to the
   * local node, beginning after the specified delay. Subsequent executions take
   * place at approximately regular intervals separated by the specified period.
   * Useful to initiate periodic tasks.
   * 
   * @param msg
   *          a message that will be delivered to the local node after the
   *          specified delay
   * @param delay
   *          time in milliseconds before message is to be delivered
   * @param period
   *          time in milliseconds between successive message deliveries
   * @return the scheduled event object; can be used to cancel the message
   */
  public ScheduledMessage scheduleMsg(Message msg, long delay, long period) {
    DirectPastryNode temp = setCurrentNode(this);
    ScheduledMessage ret = simulator.deliverMessage(msg, this, (DirectNodeHandle)this.getLocalHandle(), (int)delay, (int)period);
    setCurrentNode(temp);
    return ret;
  }

  /**
   * Schedule the specified message for repeated fixed-rate delivery to the
   * local node, beginning after the specified delay. Subsequent executions take
   * place at approximately regular intervals, separated by the specified
   * period.
   * 
   * @param msg
   *          a message that will be delivered to the local node after the
   *          specified delay
   * @param delay
   *          time in milliseconds before message is to be delivered
   * @param period
   *          time in milliseconds between successive message deliveries
   * @return the scheduled event object; can be used to cancel the message
   */
  public ScheduledMessage scheduleMsgAtFixedRate(Message msg, long delay,
      long period) {
    return simulator.deliverMessageFixedRate(msg, this, (DirectNodeHandle)this.getLocalHandle(), (int)delay, (int)period);
  }

  Hashtable nodeHandles = new Hashtable();
  public NodeHandle coalesce(NodeHandle newHandle) {
    NodeHandle ret = (NodeHandle)nodeHandles.get(newHandle);
    if (ret == null) {
      nodeHandles.put(newHandle, newHandle); 
      ret = newHandle;
    }
    return ret;
  }

  @SuppressWarnings("deprecation")
  public synchronized void receiveMessage(Message msg) {
    if (!isAlive()) return;
    // System.out.println("setting currentNode from "+currentNode+" to "+this+"
    // on "+Thread.currentThread());
    if (!getEnvironment().getSelectorManager().isSelectorThread()) {
      simulator.deliverMessage(msg, this);
      return;
    }

    // if ((currentNode != null) && (currentNode != this))
    // throw new RuntimeException("receiveMessage called recursively!");
    // System.out.println("currentNode != null");
    DirectPastryNode temp = setCurrentNode(this);
    super.receiveMessage(msg);
    setCurrentNode(temp);
  }

  public synchronized void route(RouteMessage rm) {
    if (!getEnvironment().getSelectorManager().isSelectorThread()) {
      simulator.deliverMessage(rm, this); 
      return;
    }
    
    DirectPastryNode temp = setCurrentNode(this);
    super.receiveMessage(rm);
    setCurrentNode(temp);
  }
  
  public Logger getLogger() {
    return logger;
  }
  
  public int proximity(NodeHandle that) {
    if (!that.isAlive()) return Integer.MAX_VALUE;
    float result = simulator.proximity((DirectNodeHandle) getLocalHandle(),
        (DirectNodeHandle) that);

    return (int)result;
  }

  @Override
  public void send(NodeHandle nh, Message m) {
    // shortcut if called on the local node
    if (
    // simulator.getEnvironment().getSelectorManager().isSelectorThread() &&
    // the message is from myself
    (nh == getLocalHandle())) {
      receiveMessage(m);
      return;
    }

    if (!nh.isAlive()) {
      if (logger.level <= Logger.FINE)
        logger.log("DirectNodeHandle: attempt to send message " + m
            + " to a dead node " + getNodeId() + "!");
    } else {
      int delay = (int)Math.round(simulator.networkDelay((DirectNodeHandle)localhandle, (DirectNodeHandle)nh));
      simulator.notifySimulatorListenersSent(m, this.getLocalHandle(), nh, delay);
      simulator.deliverMessage(m, ((DirectNodeHandle) nh).getRemote(), (DirectNodeHandle)this.getLocalHandle(),
          delay);
    }
  }

  public void connect(NodeHandle remoteNode, AppSocketReceiver receiver, PastryAppl appl, int timeout) {
    DirectNodeHandle dnh = (DirectNodeHandle)remoteNode;
    simulator.enqueueDelivery(new DirectAppSocket(dnh, receiver, appl, simulator).getAcceptorDelivery(),
        (int)Math.round(simulator.networkDelay((DirectNodeHandle)localhandle, dnh)));
  }

  public NodeHandle readNodeHandle(InputBuffer buf) {
    throw new RuntimeException("Should not be called.");
  }

}

