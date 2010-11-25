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
package rice.pastry.socket;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.*;
import java.nio.channels.*;
import java.util.Arrays;

import rice.Continuation;
import rice.Continuation.ExternalContinuation;
import rice.environment.Environment;
import rice.environment.logging.*;
import rice.environment.params.Parameters;
import rice.environment.processing.Processor;
import rice.environment.processing.simple.SimpleProcessor;
import rice.environment.random.RandomSource;
import rice.environment.random.simple.SimpleRandomSource;
import rice.environment.time.simulated.DirectTimeSource;
import rice.p2p.commonapi.*;
import rice.p2p.commonapi.rawserialization.*;
import rice.pastry.*;
import rice.pastry.Id;
import rice.pastry.dist.DistPastryNodeFactory;
import rice.pastry.leafset.LeafSet;
import rice.pastry.messaging.*;
import rice.pastry.routing.*;
import rice.pastry.socket.messaging.*;
import rice.pastry.socket.nat.*;
import rice.pastry.standard.*;
import rice.selector.*;
import rice.pastry.NodeHandle;
import rice.pastry.messaging.Message;

/**
 * Pastry node factory for Socket-linked nodes.
 * 
 * @version $Id$
 * @author Alan Mislove
 */
public class SocketPastryNodeFactory extends DistPastryNodeFactory {
  public static final int ALWAYS = 1;

  public static final int PREFIX_MATCH = 2;

  public static final int NEVER = 3;

  public static final int OVERWRITE = 1;

  public static final int USE_DIFFERENT_PORT = 2;

  public static final int FAIL = 3;

  private NodeIdFactory nidFactory;

  private int port;

  /**
   * Large period (in seconds) means infrequent, 0 means never.
   */
  private int leafSetMaintFreq;

  private int routeSetMaintFreq;

  private RandomSource random;

  private InetAddress localAddress;

  // the ordered list of InetAddresses, from External to internal
  protected InetAddress[] addressList;
  
  protected int testFireWallPolicy;

  protected int findFireWallPolicy;
  
  NATHandler natHandler;
  String firewallAppName;
  int firewallSearchTries;

  /**
   * Constructor.
   * 
   * Here is order for bind address 1) bindAddress parameter 2) if bindAddress
   * is null, then parameter: socket_bindAddress (if it exists) 3) if
   * socket_bindAddress doesn't exist, then InetAddress.getLocalHost()
   * 
   * @param nf The factory for building node ids
   * @param bindAddress which address to bind to
   * @param startPort The port to start creating nodes on
   * @param env The environment.
   */
  public SocketPastryNodeFactory(NodeIdFactory nf, InetAddress bindAddress,
      int startPort, Environment env, NATHandler handler) throws IOException {
    super(env);
    if (env.getTimeSource() instanceof DirectTimeSource) {
      throw new IllegalArgumentException("SocketPastryNodeFactory is not compatible with the DirectTimeSource in the environment.  Please use the SimpleTimeSource or an equivalent.");
    }
    
    environment = env;
    nidFactory = nf;
    port = startPort;
    Parameters params = env.getParameters();
    
    firewallSearchTries = params.getInt("nat_find_port_max_tries");
    firewallAppName = params.getString("nat_app_name");
    this.natHandler = handler;
    localAddress = bindAddress;
    if (localAddress == null) {
      if (params.contains("socket_bindAddress")) {
        localAddress = params.getInetAddress("socket_bindAddress");
      }
    }
    
    // user didn't specify localAddress via param or config file, ask OS
    if (localAddress == null) {
      localAddress = InetAddress.getLocalHost();
      
      Socket temp = null;
//      ServerSocket test = null;
      ServerSocket test2 = null;
//      try {
//        test = new ServerSocket();
//        test.bind(new InetSocketAddress(localAddress, port));
//      } catch (SocketException e) {
      if (localAddress.isLoopbackAddress() &&
          !params.getBoolean("pastry_socket_allow_loopback")) {
      try {
        // os gave us the loopback address, and the user doesn't want that
        
        // try the internet
        temp = new Socket(params.getString("pastry_socket_known_network_address"), 
            params.getInt("pastry_socket_known_network_address_port"));
        if (temp.getLocalAddress().equals(localAddress)) throw new IllegalStateException("Cannot bind to "+localAddress+":"+port);
        localAddress = temp.getLocalAddress();
        temp.close();
        temp = null;
        
        if (logger.level <= Logger.WARNING)
          logger.log("Error binding to default IP, using " + localAddress+":"+port);
        
        try {
          test2 = new ServerSocket();
          test2.bind(new InetSocketAddress(localAddress, port));
        } catch (SocketException e2) {
          throw new IllegalStateException("Cannot bind to "+localAddress+":"+port,e2);
        }                
      } finally {
//        try {
//          if (test != null)
//            test.close();
//        } catch (Exception e) {}
        try {
          if (test2 != null)
            test2.close();
        } catch (Exception e) {}
        try {
          if (temp != null)
            temp.close();
        } catch (Exception e) {}
      }
      }
    }

    // see if there is a firewall
    if (natHandler == null) {
      if (params.contains("nat_handler_class")) {
        try {
          Class natHandlerClass = Class.forName(params.getString("nat_handler_class"));
          Class[] args = {Environment.class, InetAddress.class};
  //        Class[] args = new Class[2];
  //        args[0] = environment.getClass();
  //        args[1] = InetAddress.class;
          Constructor constructor = natHandlerClass.getConstructor(args);
          Object[] foo = {environment, this.localAddress};
          natHandler = (NATHandler)constructor.newInstance(foo);
        } catch (ClassNotFoundException e) {
          if (logger.level <= Logger.INFO) logger.log("Didn't find UPnP libs, skipping UPnP");
          natHandler = new StubNATHandler(environment, this.localAddress);
//          natHandler = new SocketNatHandler(environment, new InetSocketAddress(localAddress,port), pAddress);
        } catch (NoClassDefFoundError e) {
          if (logger.level <= Logger.INFO) logger.log("Didn't find UPnP libs, skipping UPnP");
          natHandler = new StubNATHandler(environment, this.localAddress);
//          natHandler = new SocketNatHandler(environment, new InetSocketAddress(localAddress,port), pAddress);
        } catch (Exception e) {
          if (logger.level <= Logger.WARNING) logger.logException("Error constructing NATHandler.",e);
          throw new RuntimeException(e);
        }
      } else {
        natHandler = new StubNATHandler(environment, this.localAddress);
//      natHandler = new SBBINatHandler(environment, this.localAddress);
      }
    }
    
    leafSetMaintFreq = params.getInt("pastry_leafSetMaintFreq");
    routeSetMaintFreq = params.getInt("pastry_routeSetMaintFreq");

    if (params.contains("pastry_socket_use_own_random")
        && params.getBoolean("pastry_socket_use_own_random")) {
      if (params.contains("pastry_socket_random_seed")
          && !params.getString("pastry_socket_random_seed").equalsIgnoreCase(
              "clock")) {
        this.random = new SimpleRandomSource(params
            .getLong("pastry_socket_random_seed"), env.getLogManager(),
            "socket");
      } else {
        this.random = new SimpleRandomSource(env.getLogManager(), "socket");
      }
    } else {
      this.random = env.getRandomSource();
    }
  }

  public SocketPastryNodeFactory(NodeIdFactory nf, int startPort,
      Environment env) throws IOException {
    this(nf, null, startPort, env, null);
  }

  /**
   * This method returns the routes a remote node is using
   * 
   * @param handle The node to connect to
   * @return The leafset of the remote node
   */
  public SourceRoute[] getRoutes(NodeHandle handle, NodeHandle local)
      throws IOException {
    SocketNodeHandle wHandle = (SocketNodeHandle) handle;

    RoutesResponseMessage lm = (RoutesResponseMessage) getResponse(wHandle
        .getAddress(addressList), new RoutesRequestMessage());

    return lm.getRoutes();
  }

  /**
   * This method returns the remote leafset of the provided handle to the
   * caller, in a protocol-dependent fashion. Note that this method may block
   * while sending the message across the wire.
   * 
   * @param handle The node to connect to
   * @return The leafset of the remote node
   */
  public LeafSet getLeafSet(NodeHandle handle) throws IOException {
    SocketNodeHandle wHandle = (SocketNodeHandle) handle;

    LeafSetResponseMessage lm = (LeafSetResponseMessage) getResponse(wHandle
        .getAddress(addressList), new LeafSetRequestMessage());

    return lm.getLeafSet();
  }

  public CancellableTask getLeafSet(NodeHandle handle, final Continuation c) {
    SocketNodeHandle wHandle = (SocketNodeHandle) handle;

    return getResponse(wHandle.getAddress(addressList), new LeafSetRequestMessage(),
        new Continuation() {
          public void receiveResult(Object result) {
            LeafSetResponseMessage lm = (LeafSetResponseMessage) result;
            c.receiveResult(lm.getLeafSet());
          }

          public void receiveException(Exception result) {
            c.receiveException(result);
          }
        });
  }

  /**
   * This method returns the remote route row of the provided handle to the
   * caller, in a protocol-dependent fashion. Note that this method may block
   * while sending the message across the wire.
   * 
   * @param handle The node to connect to
   * @param row The row number to retrieve
   * @return The route row of the remote node
   */
  public RouteSet[] getRouteRow(NodeHandle handle, int row) throws IOException {
    SocketNodeHandle wHandle = (SocketNodeHandle) handle;

    RouteRowResponseMessage rm = (RouteRowResponseMessage) getResponse(wHandle
        .getAddress(addressList), new RouteRowRequestMessage(row));

    return rm.getRouteRow();
  }

  public CancellableTask getRouteRow(NodeHandle handle, int row,
      final Continuation c) {
    SocketNodeHandle wHandle = (SocketNodeHandle) handle;

    return getResponse(wHandle.getAddress(addressList), new RouteRowRequestMessage(row),
        new Continuation() {
          public void receiveResult(Object result) {
            RouteRowResponseMessage rm = (RouteRowResponseMessage) result;
            c.receiveResult(rm.getRouteRow());
          }

          public void receiveException(Exception result) {
            c.receiveException(result);
          }
        });
  }

  /**
   * This method determines and returns the proximity of the current local node
   * to the provided NodeHandle. This will need to be done in a protocol-
   * dependent fashion and may need to be done in a special way.
   * 
   * @param handle The handle to determine the proximity of
   * @param local DESCRIBE THE PARAMETER
   * @return The proximity of the provided handle
   */
  public int getProximity(NodeHandle local, NodeHandle handle) {
    EpochInetSocketAddress lAddress = ((SocketNodeHandle) local)
        .getEpochAddress();
    EpochInetSocketAddress rAddress = ((SocketNodeHandle) handle)
        .getEpochAddress();

    // lAddress = new EpochInetSocketAddress(new InetSocketAddress(lAddress
    // .getAddress().getAddress(), lAddress.getAddress().getPort() + 1));

    // if this is a request for an old version of us, then we return
    // infinity as an answer
    if (lAddress.getAddress(addressList).equals(rAddress.getAddress(addressList))) {
      return SocketNodeHandle.DEFAULT_PROXIMITY;
    }

    DatagramSocket socket = null;
    SourceRoute route = SourceRoute
        .build(new EpochInetSocketAddress[] { rAddress });

    try {
      socket = new DatagramSocket(lAddress.getAddress(addressList).getPort());
      socket.setSoTimeout(5000);

      // byte[] data = PingManager.addHeader(route, new PingMessage(route, route
      // .reverse(lAddress), environment.getTimeSource().currentTimeMillis()),
      // lAddress, environment, logger);

      SocketBuffer sb = new SocketBuffer(lAddress, route, new PingMessage(/*
                                                                           * route,
                                                                           * route
                                                                           * .reverse(lAddress),
                                                                           */environment.getTimeSource().currentTimeMillis()));

      if (logger.level <= Logger.FINE)
        logger.log("Sending Ping to " + rAddress + " from " + lAddress);
      socket.send(new DatagramPacket(sb.getBuffer().array(), sb.getBuffer()
          .limit(), rAddress.getAddress(addressList)));

      long start = environment.getTimeSource().currentTimeMillis();
      socket.receive(new DatagramPacket(new byte[10000], 10000));
      return (int) (environment.getTimeSource().currentTimeMillis() - start);
    } catch (IOException e) {
      if (logger.level <= Logger.FINE) logger.logException("Error in getProximity("+local+","+handle+") ",e);
      return SocketNodeHandle.DEFAULT_PROXIMITY;
    } finally {
      if (socket != null)
        socket.close();
    }
  }

  /**
   * Way to generate a NodeHandle with a maximum timeout to receive the result.
   * Helper funciton for using the non-blocking version. However this method
   * behaves as a blocking call.
   * 
   * @param address
   * @param timeout maximum time in millis to return the result. <= 0 will use
   *          the blocking version.
   * @return
   */
  public NodeHandle generateNodeHandle(InetSocketAddress address, int timeout) {
    if (timeout <= 0)
      return generateNodeHandle(address);

    TimerContinuation c = new TimerContinuation();

    CancellableTask task = generateNodeHandle(address, c);

    if (task == null)
      return null;

    synchronized (c) {
      try {
        c.wait(timeout);
      } catch (InterruptedException ie) {
        return null;
      }
    }
    task.cancel();

    if (logger.level <= Logger.FINER)
      logger.log("SPNF.generateNodeHandle() returning " + c.ret
          + " after trying to contact " + address);

    return (NodeHandle) c.ret;
  }
  
  /**
   * Specify the InetSocketAddresses of the NATted node, from exterior to interior.  This call
   * should work from within or outside of the NAT.
   * 
   * @param address
   * @return
   */
  public NodeHandle getNATtedNodeHandle(InetSocketAddress[] address) {
    return generateNodeHandle(address, 0);
  }

  protected NodeHandle generateNodeHandle(InetSocketAddress[] address, int addrNum) {
    // send nodeId request to remote node, wait for response
    // allocate enough bytes to read a node handle
    if (logger.level <= Logger.FINE)
      logger.log("Socket: Contacting bootstrap node " + address[addrNum]);

    try {
      NodeIdResponseMessage rm = (NodeIdResponseMessage) getResponse(address[addrNum],
          new NodeIdRequestMessage());

      return new SocketNodeHandle(new EpochInetSocketAddress(address, rm.getEpoch()), rm.getNodeId());
    } catch (IOException e) {
      if(addrNum+1 < address.length) {
        return generateNodeHandle(address, addrNum+1);
      }

      if (logger.level <= Logger.FINE) {
        logger.logException("Error connecting to addresses " + Arrays.toString(address) + ": ", e);
      } else {
        if (logger.level <= Logger.WARNING) logger.log("Error connecting to addresses " + Arrays.toString(address) + ": " + e);
      }

      return null;
    }
  }
  
  static class TimerContinuation implements Continuation {
    public Object ret = null;
    public Exception exception = null;
    
    public void receiveResult(Object result) {
      synchronized (this) {
        ret = result;
        this.notify();
      }
    }

    public void receiveException(Exception result) {
      synchronized (this) {
        this.exception = result;
        this.notify();
      }
    }
  }

  /**
   * Method which contructs a node handle (using the socket protocol) for the
   * node at address NodeHandle.
   * 
   * @param address The address of the remote node.
   * @return A NodeHandle cooresponding to that address
   */
  public NodeHandle generateNodeHandle(InetSocketAddress address) {
    // send nodeId request to remote node, wait for response
    // allocate enought bytes to read a node handle
    if (logger.level <= Logger.FINE)
      logger.log("Socket: Contacting bootstrap node " + address);

    try {
      NodeIdResponseMessage rm = (NodeIdResponseMessage) getResponse(address,
          new NodeIdRequestMessage());

      return new SocketNodeHandle(new EpochInetSocketAddress(address, rm
          .getEpoch()), rm.getNodeId());
    } catch (IOException e) {
      if (logger.level <= Logger.FINE) {
        logger.logException("Error connecting to address " + address + ": ", e);
      } else {
        if (logger.level <= Logger.WARNING)
          logger.log("Error connecting to address " + address + ": " + e);
      }
      return null;
    }
  }

  public CancellableTask generateNodeHandle(final InetSocketAddress address,
      final Continuation c) {
    if (logger.level <= Logger.FINE)
      logger.log("Socket: Contacting bootstrap node " + address);

    return getResponse(address, new NodeIdRequestMessage(), new Continuation() {
      public void receiveResult(Object result) {
        NodeIdResponseMessage rm = (NodeIdResponseMessage) result;
        c.receiveResult(new SocketNodeHandle(new EpochInetSocketAddress(
            address, rm.getEpoch()), rm.getNodeId()));
      }

      public void receiveException(Exception result) {
        if (logger.level <= Logger.WARNING)
          logger.log("Error connecting to address " + address + ": " + result);
        c.receiveException(result);
      }
    });
  }

  /**
   * Method which creates a Pastry node from the next port with a randomly
   * generated NodeId.
   * 
   * @param bootstrap Node handle to bootstrap from.
   * @return A node with a random ID and next port number.
   */
  public PastryNode newNode(NodeHandle bootstrap) {
    // if (bootstrap == null) {
    // return newNode(bootstrap, NodeId.buildNodeId());
    // }
    return newNode(bootstrap, nidFactory.generateNodeId());
  }

  /**
   * Method which creates a Pastry node from the next port with a randomly
   * generated NodeId.
   * 
   * @param bootstrap Node handle to bootstrap from.
   * @param nodeId DESCRIBE THE PARAMETER
   * @return A node with a random ID and next port number.
   */
  public PastryNode newNode(final NodeHandle bootstrap, Id nodeId) {
    return newNode(bootstrap, nodeId, null);
  }

  /**
   * Method which creates a Pastry node from the next port with a randomly
   * generated NodeId.
   * 
   * @param bootstrap Node handle to bootstrap from.
   * @return A node with a random ID and next port number.
   */
  public PastryNode newNode(NodeHandle bootstrap, InetSocketAddress proxy) {
    return newNode(bootstrap, nidFactory.generateNodeId(), proxy);
  }

  /**
   * Method which creates a Pastry node from the next port with a randomly
   * generated NodeId.
   * 
   * @param bootstrap Node handle to bootstrap from.
   * @param nodeId DESCRIBE THE PARAMETER
   * @param pAddress The address to claim that this node is at - used for proxies
   *          behind NATs
   * @return A node with a random ID and next port number.
   */
  public synchronized PastryNode newNode(NodeHandle bootstrap, Id nodeId,
      InetSocketAddress pAddress) {
    try {
      return newNode(bootstrap, nodeId, pAddress, true); // fix the method just
                                                          // below if you change
                                                          // this
    } catch (BindException e) {

      if (logger.level <= Logger.WARNING)
        logger.log("Warning: " + e);

      if (environment.getParameters().getBoolean(
          "pastry_socket_increment_port_after_construction")) {
        port++;
        try {
          return newNode(bootstrap, nodeId, pAddress); // recursion, this will
                                                        // prevent from things
                                                        // getting too out of
                                                        // hand in
          // case the node can't bind to anything, expect a
          // StackOverflowException
        } catch (StackOverflowError soe) {
          if (logger.level <= Logger.SEVERE)
            logger
                .log("SEVERE: SocketPastryNodeFactory: Could not bind on any ports!"
                    + soe);
          throw soe;
        }
      } else {
        
        // clean up Environment
        if (this.environment.getParameters().getBoolean(
            "pastry_factory_multipleNodes")) {
          environment.destroy();
        }
        
        throw new RuntimeException(e);
      }
    } catch (IOException ioe) {
      
      throw new RuntimeException(ioe);      
    }
  }

  public synchronized PastryNode newNode(NodeHandle bootstrap, Id nodeId,
      InetSocketAddress pAddress, boolean throwException) throws IOException {
    if (!throwException)
      return newNode(bootstrap, nodeId, pAddress); // yes, this is sort of
                                                    // bizarre
    // the idea is that we can't throw an exception by default because it will
    // break reverse compatibility
    // so this method gets called twice if throwException is false. But the
    // second time,
    // it will be called with true, but will be
    // wrapped with the above function which will catch the exception.
    // -Jeff May 12, 2006
    if (bootstrap == null)
      if (logger.level <= Logger.WARNING)
        logger
            .log("No bootstrap node provided, starting a new ring binding to address "
                + localAddress + ":" + port + "...");

    // this code builds a different environment for each PastryNode
    Environment environment = this.environment;
    if (this.environment.getParameters().getBoolean(
        "pastry_factory_multipleNodes")) {
      if (this.environment.getLogManager() instanceof CloneableLogManager) {
        LogManager lman = ((CloneableLogManager) this.environment
            .getLogManager()).clone("0x" + nodeId.toStringBare());
        SelectorManager sman = this.environment.getSelectorManager();
        Processor proc = this.environment.getProcessor();
        if (this.environment.getParameters().getBoolean(
            "pastry_factory_selectorPerNode")) {
          sman = new SelectorManager(nodeId.toString() + " Selector",
              this.environment.getTimeSource(), lman);
        }
        if (this.environment.getParameters().getBoolean(
            "pastry_factory_processorPerNode")) {
          proc = new SimpleProcessor(nodeId.toString() + " Processor");
        }

        environment = new Environment(sman, proc, this.environment
            .getRandomSource(), this.environment.getTimeSource(), lman,
            this.environment.getParameters(), this.environment.getExceptionStrategy());

        this.environment.addDestructable(environment);
      }
    }

    // NOTE: We _don't_ want to use the environment RandomSource because this
    // will cause
    // problems if we run the same node twice quickly with the same seed. Epochs
    // should really
    // be different every time.
    long epoch = random.nextLong();

    EpochInetSocketAddress localAddress = null;
    EpochInetSocketAddress proxyAddress = null;

    // getNearest uses the port inside the SNH, so this needs to be the local
    // address
    NodeHandle[] nearest; // = getNearest(temp, bootstrap);

    final SocketPastryNode pn = new SocketPastryNode(nodeId, environment);

    environment.addDestructable(pn);
    
    SocketSourceRouteManager srManager = null;

    try {
        localAddress = getEpochAddress(port, epoch);
                
        boolean probeForExternalAddress = environment.getParameters().getBoolean("probe_for_external_address");
        if (pAddress == null) {
          if (environment.getParameters().contains("external_address")) {
            pAddress = environment.getParameters().getInetSocketAddress("external_address");
          } else {
            if (probeForExternalAddress) {
              int timeout = environment.getParameters().getInt("pastry_proxy_connectivity_timeout");
              int tries = environment.getParameters().getInt("pastry_proxy_connectivity_tries");
              if (bootstrap == null) {
                throw new IOException("Cannot probe for external address without a bootstrap node to use as a probe target");
              }
              
              InetSocketAddress[] verifyAddresses = new InetSocketAddress[1];
              verifyAddresses[0] = ((SocketNodeHandle)bootstrap).eaddress.address[0]; // if the bootstrap is going to be used to detect the external address, we have to use its internet address
              pAddress = verifyConnection(timeout, tries, localAddress.getInnermostAddress(), verifyAddresses, environment, logger);
            }
          }
        }
        
        // pAddress is null only if !probeForExternalAddress, but may not be null
        // proxyAddress is null
        if (!probeForExternalAddress) {
          findFireWallIfNecessary();
          if (pAddress == null) {
            // may need to find and set the firewall
            if (natHandler.getFireWallExternalAddress() == null) {
              proxyAddress = localAddress;
            } else {
              // configure the firewall if necessary, can be any port, start with
              // the freepastry port
              int availableFireWallPort = natHandler.findAvailableFireWallPort(port, port, firewallSearchTries, firewallAppName);
              natHandler.openFireWallPort(port, availableFireWallPort, firewallAppName);
              proxyAddress = new EpochInetSocketAddress(new InetSocketAddress[]{new InetSocketAddress(
                  natHandler.getFireWallExternalAddress(), availableFireWallPort), localAddress.getInnermostAddress()}, epoch);
            }
          } else {
            // configure the firewall if necessary, but to the specified port
            if (natHandler.getFireWallExternalAddress() != null) {
              int availableFireWallPort = natHandler.findAvailableFireWallPort(port,
                  pAddress.getPort(), firewallSearchTries, firewallAppName);
              if (availableFireWallPort == pAddress.getPort()) {
                natHandler.openFireWallPort(port, availableFireWallPort, firewallAppName);
              } else {
                // decide how to handle this
                switch (getFireWallPolicyVariable("nat_state_policy")) {
                  case OVERWRITE:
                    natHandler.openFireWallPort(port, pAddress.getPort(), firewallAppName);
                    break;
                  case FAIL:
                    // todo: would be useful to pass the app that is bound to that
                    // port
                    throw new BindException(
                        "Firewall is already bound to the requested port:"
                            + pAddress);
                  case USE_DIFFERENT_PORT:
                    natHandler.openFireWallPort(port, availableFireWallPort, firewallAppName);
                    pAddress = new InetSocketAddress(pAddress.getAddress(),
                        availableFireWallPort);
                    break;
                }
              }
            }
            proxyAddress = new EpochInetSocketAddress(new InetSocketAddress[]{pAddress, localAddress.getInnermostAddress()}, epoch);
          }
        } else {
          proxyAddress = new EpochInetSocketAddress(new InetSocketAddress[]{pAddress, localAddress.getInnermostAddress()}, epoch);          
        }
        
        updateAddressList(proxyAddress);
        
        SocketNodeHandle temp = new SocketNodeHandle(proxyAddress,
            nodeId);
        nearest = getNearest(temp, bootstrap);

        srManager = new SocketSourceRouteManager(pn, localAddress,
            proxyAddress, random); // throws an exception if cant bind
        if (environment.getParameters().getBoolean(
            "pastry_socket_increment_port_after_construction"))
          port++; // this statement must go after the construction of srManager
                  // because the
    } catch (IOException ioe) {
      // this will usually be a bind exception

      throw ioe;
//    } catch (UPNPResponseException ure) {
//      throw new RuntimeException(ure);
    }

    // calling method can decide what to do with port incrementation if can't
    // bind

    pn.setSocketSourceRouteManager(srManager);
    SocketNodeHandle localhandle = new SocketNodeHandle(proxyAddress, nodeId);
    localhandle = (SocketNodeHandle) pn.coalesce(localhandle);
    MessageDispatch msgDisp = new MessageDispatch(pn);
    RoutingTable routeTable = new RoutingTable(localhandle, rtMax, rtBase,
        pn);
    LeafSet leafSet = new LeafSet(localhandle, lSetSize, routeTable);

    StandardRouter router = new StandardRouter(pn);

    StandardRouteSetProtocol rsProtocol = new StandardRouteSetProtocol(pn,
        routeTable, environment);

    pn.setElements(localhandle, msgDisp, leafSet, routeTable);
    router.register();
    rsProtocol.register();
    pn.setSocketElements(proxyAddress, leafSetMaintFreq, routeSetMaintFreq);

    PeriodicLeafSetProtocol lsProtocol = new PeriodicLeafSetProtocol(pn,
        localhandle, leafSet, routeTable);
    lsProtocol.register();
    ConsistentJoinProtocol jProtocol = new ConsistentJoinProtocol(pn,
        localhandle, routeTable, leafSet, lsProtocol);
    jProtocol.register();

    if (bootstrap != null) {
      bootstrap = (SocketNodeHandle) pn.coalesce(bootstrap);

      switch (getFireWallPolicyVariable("firewall_test_policy")) {
        case NEVER:
          break;
        case PREFIX_MATCH:
          if (!localAddressIsProbablyNatted())
            break;
        case ALWAYS:
        default:
          ExternalContinuation ec = new ExternalContinuation();
          pn.testFireWall(bootstrap, ec, 5000, 3);
          ec.sleep();
          Boolean resultB = (Boolean) ec.getResult();
          boolean result = resultB.booleanValue();
          if (result) {
            // continue
          } else { // should change to IOException in FP 2.0
            throw new RuntimeException("Firewall test failed for local:"
                + proxyAddress.getInnermostAddress() + " external:"
                + proxyAddress.address[0]);
          }
      } // switch
    }

    // asdf // why is this here?
//    try {
//      Thread.sleep(1000);
//    } catch (InterruptedException e) {
//    }

//    NodeHandle nearest = getNearest(temp, bootstrap);
    if (nearest != null)
      if (nearest.length > 0) {
        for(int i = 0; i < nearest.length; i++) {
          nearest[i] = pn.coalesce(nearest[i]);
        }
      }
    
    pn.doneNode(nearest);
    // pn.doneNode(bootstrap);

    return pn;
  }

  /**
   * This list is the list of addresses of the local computer.  This is needed
   * to get correct routing if you are on the same LAN, and your router doesn't
   * support hairpinning.
   * 
   * @param proxyAddress
   */
  private void updateAddressList(EpochInetSocketAddress proxyAddress) {
    addressList = new InetAddress[proxyAddress.address.length];
    for (int i = 0; i < addressList.length; i++) {
      addressList[i] = proxyAddress.address[i].getAddress(); 
    }
  }

  MessageDeserializer deserializer = new SPNFDeserializer();

  // this one doesn't properly coalsece NodeHandles, but when this is used,
  // there is no PastryNode yet!
  NodeHandleFactory nhf = new NodeHandleFactory() {
    public NodeHandle readNodeHandle(InputBuffer buf) throws IOException {
      return SocketNodeHandle.build(buf);
    }
  };

  class SPNFDeserializer implements MessageDeserializer {
    public rice.p2p.commonapi.Message deserialize(InputBuffer buf, short type,
        int priority, rice.p2p.commonapi.NodeHandle sender) throws IOException {
      switch (type) {
        case NodeIdResponseMessage.TYPE:
          return new NodeIdResponseMessage(buf);
        case LeafSetResponseMessage.TYPE:
          return new LeafSetResponseMessage(buf, nhf);
        case RoutesResponseMessage.TYPE:
          return new RoutesResponseMessage(buf);
        case RouteRowResponseMessage.TYPE:
          return new RouteRowResponseMessage(buf, nhf, null);
        default:
          if (logger.level <= Logger.SEVERE)
            logger.log("SERIOUS ERROR: Received unknown message address: " + 0
                + "type:" + type);
          return null;
      }
    }
  }

  protected void findFireWallIfNecessary() throws IOException {
    switch (getFireWallPolicyVariable("nat_search_policy")) {
      case NEVER:
        return;
      case PREFIX_MATCH:
        if (!localAddressIsProbablyNatted())
          return;
      case ALWAYS:
      default:
        natHandler.findFireWall(localAddress);
    }
  }

  protected int getFireWallPolicyVariable(String key) {
    String val = environment.getParameters().getString(key);
    if (val.equalsIgnoreCase("prefix"))
      return PREFIX_MATCH;
    if (val.equalsIgnoreCase("change"))
      return USE_DIFFERENT_PORT;
    if (val.equalsIgnoreCase("never"))
      return NEVER;
    if (val.equalsIgnoreCase("overwrite"))
      return OVERWRITE;
    if (val.equalsIgnoreCase("always"))
      return ALWAYS;
    if (val.equalsIgnoreCase("fail"))
      return FAIL;
    throw new RuntimeException("Unknown value " + val + " for " + key);
  }

  /**
   * @return true if ip address matches firewall prefix
   */
  protected boolean localAddressIsProbablyNatted() {
    String ip = localAddress.getHostAddress();
    String nattedNetworkPrefixes = environment.getParameters().getString(
        "nat_network_prefixes");

    String[] nattedNetworkPrefix = nattedNetworkPrefixes.split(";");
    for (int i = 0; i < nattedNetworkPrefix.length; i++) {
      if (ip.startsWith(nattedNetworkPrefix[i])) {
        return true;
      }
    }
    return false;
  }

  /**
   * This method anonymously sends the given message to the remote address,
   * blocks until a response is received, and then closes the socket and returns
   * the response.
   * 
   * @param address The address to send to
   * @param message The message to send
   * @return The response
   * @exception IOException DESCRIBE THE EXCEPTION
   */
  protected Message getResponse(InetSocketAddress address, Message message)
      throws IOException {
    // create reader and writer
    SocketChannelWriter writer;
    SocketChannelReader reader;
    writer = new SocketChannelWriter(environment, SourceRoute
        .build(new EpochInetSocketAddress(address,
            EpochInetSocketAddress.EPOCH_UNKNOWN)));
    reader = new SocketChannelReader(environment, SourceRoute
        .build(new EpochInetSocketAddress(address,
            EpochInetSocketAddress.EPOCH_UNKNOWN)));

    // bind to the appropriate port
    SocketChannel channel = SocketChannel.open();
    channel.configureBlocking(true);
    channel.socket().connect(address, 20000);
    channel.socket().setSoTimeout(20000);

    writer.enqueue(TOTAL_HEADER);
    writer.enqueue(message);
    writer.write(channel);
    SocketBuffer o = null;

    while (o == null) {
      o = reader.read(channel);
    }

    if (logger.level <= Logger.FINER)
      logger.log("SPNF.getResponse(): Closing " + channel);
    channel.socket().shutdownOutput();
    channel.socket().close();
    channel.close();
    if (logger.level <= Logger.FINER)
      logger.log("SPNF.getResponse(): Closed " + channel);

    return o.deserialize(deserializer);
  }

  static final byte[] TOTAL_HEADER;
  static {
    TOTAL_HEADER = new byte[SocketCollectionManager.TOTAL_HEADER_SIZE + 4]; // plus
                                                                            // the
                                                                            // appId
    System.arraycopy(SocketCollectionManager.PASTRY_MAGIC_NUMBER, 0,
        TOTAL_HEADER, 0, SocketCollectionManager.PASTRY_MAGIC_NUMBER.length);
    // System.arraycopy(new byte[4],0,TOTAL_HEADER,4,4); // version 0 // can
    // just leave blank zero, cause java zeros everything automatically
    System.arraycopy(SocketCollectionManager.HEADER_DIRECT, 0, TOTAL_HEADER, 8,
        SocketCollectionManager.HEADER_SIZE);
    // System.arraycopy(new byte[4],0,TOTAL_HEADER,12,4); // appId 0 // can just
    // leave blank zero, cause java zeros everything automatically
  }

  protected CancellableTask getResponse(final InetSocketAddress address,
      final Message message, final Continuation c) {
    // create reader and writer
    final SocketChannelWriter writer;
    final SocketChannelReader reader;
    writer = new SocketChannelWriter(environment, SourceRoute
        .build(new EpochInetSocketAddress(address,
            EpochInetSocketAddress.EPOCH_UNKNOWN)));
    reader = new SocketChannelReader(environment, SourceRoute
        .build(new EpochInetSocketAddress(address,
            EpochInetSocketAddress.EPOCH_UNKNOWN)));
    writer.enqueue(TOTAL_HEADER);
    try {
      writer.enqueue(message);
    } catch (IOException ioe) {
      c.receiveException(ioe);
      return null;
    }

    // bind to the appropriate port
    try {
      final SocketChannel channel = SocketChannel.open();
      channel.configureBlocking(false);
      final SelectionKey key = environment.getSelectorManager().register(
          channel, new SelectionKeyHandler() {
            public void connect(SelectionKey key) {
              if (logger.level <= Logger.FINE)
                logger.log("SPNF.getResponse(" + address + "," + message
                    + ").connect()");
              try {
                if (channel.finishConnect())
                  key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);

                if (logger.level <= Logger.FINE)
                  logger
                      .log("(SPNF) Found connectable channel - completed connection");
                // channel.socket().connect(address, 20000);
                // channel.socket().setSoTimeout(20000);
              } catch (IOException ioe) {
                handleException(ioe);
              }
            }

            public void read(SelectionKey key) {
              if (logger.level <= Logger.FINE)
                logger.log("SPNF.getResponse(" + address + "," + message
                    + ").read()");
              try {
                SocketBuffer o = null;

                while (o == null) {
                  o = reader.read(channel);
                }
                channel.socket().close();
                channel.close();
                key.cancel();
                c.receiveResult(o.deserialize(deserializer));
              } catch (IOException ioe) {
                handleException(ioe);
              }
            }

            public void write(SelectionKey key) {
              if (logger.level <= Logger.FINE)
                logger.log("SPNF.getResponse(" + address + "," + message
                    + ").write()");
              try {
                if (writer.write(channel)) {
                  key.interestOps(SelectionKey.OP_READ);
                }
              } catch (IOException ioe) {
                handleException(ioe);
              }
            }

            public void handleException(Exception e) {
              try {
                channel.socket().close();
                channel.close();
                channel.keyFor(environment.getSelectorManager().getSelector())
                    .cancel();
              } catch (IOException ioe) {

                if (logger.level <= Logger.WARNING)
                  logger.logException("Error while trying requesting "
                      + message + " from " + address, e);
              } finally {
                c.receiveException(e);
              }
            }
          }, 0);

      if (logger.level <= Logger.FINE)
        logger.log("(SPNF) Initiating socket connection to address " + address);

      if (channel.connect(address))
        key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
      else
        key.interestOps(SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE
            | SelectionKey.OP_READ);

      return new CancellableTask() {
        public void run() {
        }

        public boolean cancel() {
          environment.getSelectorManager().invoke(new Runnable() {
            public void run() {

              try {
                synchronized (key) {
                  channel.socket().close();
                  channel.close();
                  // if (logger.level <= Logger.WARNING) {
                  // if (!environment.getSelectorManager().isSelectorThread()) {
                  // logger.logException("WARNING: cancelling key:"+key+" on the
                  // wrong thread.", new Exception("Stack Trace"));
                  // }
                  // }
                  key.cancel();
                }
                // return true;
              } catch (Exception ioe) {
                if (logger.level <= Logger.WARNING)
                  logger.logException("Error cancelling task.", ioe);
                // return false;
              }
            }
          });
          return true;
        }

        public long scheduledExecutionTime() {
          return 0;
        }
      };
    } catch (IOException ioe) {
      c.receiveException(ioe);
      return null;
    }
  }

  /**
   * Method which constructs an InetSocketAddres for the local host with the
   * specifed port number.
   * 
   * @param portNumber The port number to create the address at.
   * @return An InetSocketAddress at the localhost with port portNumber.
   */
  private EpochInetSocketAddress getEpochAddress(int portNumber, long epoch) {
    EpochInetSocketAddress result = null;

    result = new EpochInetSocketAddress(new InetSocketAddress(localAddress,
        portNumber), epoch);
    return result;
  }

  /**
   * Method which can be used to test the connectivity contstrains of the local
   * node. This (optional) method is designed to be called by applications to
   * ensure that the local node is able to connect through the network - checks
   * can be done to check TCP/UDP connectivity, firewall setup, etc...
   * 
   * If the method works, then nothing should be done and the method should
   * return. If an error condition is detected, an exception should be thrown.
   */
  public static InetSocketAddress verifyConnection(int timeout,
      InetSocketAddress local, InetSocketAddress[] existing, Environment env,
      Logger logger) throws IOException {
    
    return verifyConnection(timeout, 1, local, existing, env, logger);
  }
  
  public static InetSocketAddress verifyConnection(int timeout, int tries,
      InetSocketAddress local, InetSocketAddress[] existingInput, Environment env,
      Logger logger) throws IOException {
    
    // You can't use yourself as the existing node to verify off of, this 
    // code removes yourself from existingInput, and builds the array "existing"
    int existingLength = 0;
    for (int i = 0; i < existingInput.length; i++) {
      if (existingInput[i].equals(local)) {
        // don't increment 
      } else {
        existingLength++; 
      }
    }
    
    if (existingLength == 0) {
      // there is a problem
      if (existingInput.length == 0) {
        throw new IllegalArgumentException("verifyConnection("+local+") called without any addresses to connect to.");
      } else {
        throw new IllegalArgumentException("verifyConnection("+local+","+existingInput[0]+") called with only self as address to connect to, this is not allowed.");        
      }
    }
      
    InetSocketAddress[] existing = new InetSocketAddress[existingLength];
    int index = 0;
    for (int i = 0; i < existingInput.length; i++) {
      if (existingInput[i].equals(local)) {
        // don't add
      } else {
        existing[index] = existingInput[i];
        index++; 
      }
    }
    
    
    if (logger.level <= Logger.INFO)
      logger.log("Verifying connection of local node " + local + " using "
          + existing[0] + " and " + existing.length + " more");
    DatagramSocket socket = null;

    try {
      socket = new DatagramSocket(local);
//      socket.setSoTimeout(timeout);

      IOException toThrow = null;
      // retry loop
      // TODO: need socket to be non-blocking to do this correctly, but do this later, in the meantime, the looping will be outside
      int subTimeout = timeout/(int)(Math.pow(2,tries)-1); // a function of timeout and tries
      if (subTimeout < 1) subTimeout = 1;  
      for (int curTry = 0; curTry < tries; curTry++) {
        
        socket.setSoTimeout(subTimeout);
      // probe each node
        for (int i = 0; i < existing.length; i++) {
          // byte[] buf = PingManager
          // .addHeader(SourceRoute
          // .build(new EpochInetSocketAddress(existing[i])),
          // new IPAddressRequestMessage(env.getTimeSource()
          // .currentTimeMillis()), new EpochInetSocketAddress(local),
          // env, logger);
          SocketBuffer sb = new SocketBuffer(
              new EpochInetSocketAddress(local),
              SourceRoute.build(new EpochInetSocketAddress(existing[i])),
              new IPAddressRequestMessage(env.getTimeSource().currentTimeMillis()));
          DatagramPacket send = new DatagramPacket(sb.getBuffer().array(), sb
              .getBuffer().limit(), existing[i]);
          socket.send(send);
        }
  
        DatagramPacket receive = new DatagramPacket(new byte[10000], 10000);
        try {
          socket.receive(receive);
        } catch (SocketTimeoutException e) {
          toThrow = e;
        }
        subTimeout*=2;

//        [39, 64, 117, 58, 0, 0, 0, 0,  1, 2   , 0, 30, 1, -64, -88, 1, 65, 35, 47, -1, -1, -1, -1, -1, -1, -1, -1, 1, -64, -88, 1, 65, 35, 47, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0,  0,  0, 0, 2, 0, 0, 1, 15, -96, 16, 47, 100, ]
//        | Magic Number  | version   |hop|nhops|length|len| inetaddr      | port  | epoch                         |len| inetaddr      | port  | epoch                         | app addr  |sdr|pri| type|  long timestamp              | 
//                                                     | EISA  (local)                                             |  SR EISA[]                                                | 
        int headerLength = 42;
        if (receive.getLength() > headerLength) {
          byte[] data = new byte[receive.getLength() - headerLength];
          System.arraycopy(receive.getData(), headerLength, data, 0, data.length);
    
          return ((IPAddressResponseMessage) new SocketBuffer(data, null)
              .deserialize(new PingManager.PMDeserializer(logger))).getAddress();
        }
      } // retry loop
      throw toThrow;
      // return ((IPAddressResponseMessage) PingManager.deserialize(data, env,
      // null, logger)).getAddress();
    } finally {
      if (socket != null)
        socket.close();
    }
  }
}
