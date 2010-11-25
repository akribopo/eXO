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

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

import rice.environment.Environment;
import rice.environment.logging.Logger;
import rice.environment.params.Parameters;
import rice.environment.time.TimeSource;
import rice.p2p.commonapi.rawserialization.*;
import rice.p2p.util.*;
import rice.pastry.*;
import rice.pastry.messaging.PRawMessage;
import rice.pastry.socket.messaging.*;
import rice.selector.*;

/**
 * @version $Id$
 * @author jeffh To change the template for this generated type comment go to
 *      Window>Preferences>Java>Code Generation>Code and Comments
 */
public class PingManager extends SelectionKeyHandler {
  
  public static final int PING_THROTTLE = 500;

//  private static final short SHORT_PING_TYPE = 159;
//  private static final short SHORT_PING_RESPONSE_TYPE = 160;

  // whether or not we should use short pings
//  public final boolean USE_SHORT_PINGS;// = false;
  
  // the header which signifies a normal socket
//  protected static byte[] HEADER_PING = new byte[] {0x49, 0x3A, 0x09, 0x5C};
  
  // the header which signifies a new, shorter ping
//  protected static byte[] HEADER_SHORT_PING = new byte[] {0x31, 0x1C, 0x0E, 0x11};
  
  // the header which signifies a new, shorter ping
//  protected static byte[] HEADER_SHORT_PING_RESPONSE = new byte[] {0x31, 0x1C, 0x0E, 0x12};  
  
  // the length of the ping header
  public static final int HEADER_SIZE = SocketCollectionManager.PASTRY_MAGIC_NUMBER.length;

  // the size of the buffer used to read incoming datagrams must be big enough
  // to encompass multiple datagram packets
  public final int DATAGRAM_RECEIVE_BUFFER_SIZE;
  
  // the size of the buffer used to send outgoing datagrams this is also the
  // largest message size than can be sent via UDP
  public final int DATAGRAM_SEND_BUFFER_SIZE;
  
  public final int MIN_RTT = 2;
  
  // SourceRoute -> ArrayList of PingResponseListener
  protected Map pingListeners;

  // SourceRoute -> Long 
  protected Map lastPingTime;
  
  // The list of pending meesages
  protected ArrayList pendingMsgs;

  // the buffer used for writing datagrams
  private ByteBuffer buffer;

  // the channel used from talking to the network
  private DatagramChannel channel;

  // the key used to determine what has taken place
  private SelectionKey key;
  
  // the source route manager
  private SocketSourceRouteManager manager;
  
  // the local address of this node
  private EpochInetSocketAddress localAddress;
  
  // the local node
  private SocketPastryNode spn;
  
  private Logger logger;
  
  private TimeSource timeSource;
  
  private Environment environment;
  
  private boolean testSourceRouting;
  
  /**
   * @param port DESCRIBE THE PARAMETER
   * @param manager DESCRIBE THE PARAMETER
   * @param pool DESCRIBE THE PARAMETER
   */
  public PingManager(SocketPastryNode spn, SocketSourceRouteManager manager, EpochInetSocketAddress bindAddress, EpochInetSocketAddress proxyAddress) throws IOException {
    this.spn = spn;
    this.environment = spn.getEnvironment();
    this.logger = environment.getLogManager().getLogger(PingManager.class, null);
    this.deserializer = new PMDeserializer(logger);
    this.timeSource = environment.getTimeSource();
    
    Parameters p = environment.getParameters();
    pingListeners = new TimerWeakHashMap(environment.getSelectorManager().getTimer(), 60000);
    lastPingTime = new TimerWeakHashMap(environment.getSelectorManager().getTimer(), 5000); 
    this.manager = manager;
    this.pendingMsgs = new ArrayList();
    this.localAddress = proxyAddress;
    testSourceRouting = p.getBoolean("pastry_socket_pingmanager_testSourceRouting");
//    USE_SHORT_PINGS = p.getBoolean("pastry_socket_pingmanager_smallPings"); 
    DATAGRAM_RECEIVE_BUFFER_SIZE = p.getInt("pastry_socket_pingmanager_datagram_receive_buffer_size");
    DATAGRAM_SEND_BUFFER_SIZE = p.getInt("pastry_socket_pingmanager_datagram_send_buffer_size");

    // allocate enough bytes to read data
    this.buffer = ByteBuffer.allocate(DATAGRAM_SEND_BUFFER_SIZE);

    try {
      // bind to the appropriate port
      channel = DatagramChannel.open();
      channel.configureBlocking(false);
      channel.socket().setReuseAddress(true);
      channel.socket().bind(bindAddress.getInnermostAddress());
      channel.socket().setSendBufferSize(DATAGRAM_SEND_BUFFER_SIZE);
      channel.socket().setReceiveBufferSize(DATAGRAM_RECEIVE_BUFFER_SIZE);

      key = environment.getSelectorManager().register(channel, this, 0);
      key.interestOps(SelectionKey.OP_READ);
      if (logger.level <= Logger.INFO) logger.log("PingManager binding to "+bindAddress);
    } catch (IOException e) {
//      if (logger.level <= Logger.SEVERE) logger.log(
//          "PANIC: Error binding datagram server to address " + localAddress + ": " + e);
      throw e;
    }
  }
  
  /**
   *        ----- EXTERNAL METHODS -----
   */
  
  /**
   * Method which actually sends a ping to over the specified path, and returns the result
   * to the specified listener.  Note that if no ping response is ever received, the 
   * listener is never called.
   *
   * @param path The path to send the ping over
   * @param prl The listener which should hear about the response
   */
  protected void ping(SourceRoute path, PingResponseListener prl) {
    if (prl == null && path.getLastHop().equals(localAddress)) return;
    // this code is to throttle pings
    // I don't know what to do if there is a prl, because it is difficult to know 
    // if there is still an outstanding ping, so we can only throttle if there is no
    // prl
    long curTime = timeSource.currentTimeMillis();
    if (prl == null) {
      Long time = (Long)lastPingTime.get(path); 
      if (time != null) {
        if ((time.longValue()+PING_THROTTLE) > curTime) {
          if (logger.level <= Logger.FINE) logger.log(
              "(PM) Suppressing ping via path " + path + " local " + localAddress);
          return;          
        }
      }
    }
    
    if (logger.level <= Logger.FINE) logger.log(
        "(PM) Sending Ping["+curTime+"] via path " + path + "("+path.hashCode()+") local " + localAddress+" for "+prl);

    lastPingTime.put(path, Long.valueOf(curTime));
    
    addPingResponseListener(path, prl);    
    
//    if (USE_SHORT_PINGS)
//      sendShortPing(path);
//    else
      enqueue(path, new PingMessage(curTime));
  }
  
  /**
   * Makes this node resign from the network.  Is designed to be used for
   * debugging and testing.
   */
  protected void resign() throws IOException {
    if (key != null) {
      if (key.channel() != null)
        key.channel().close();
      key.cancel();
      key.attach(null);
    }
  }
  
  /**
   * Internal testing method which simulates a stall. DO NOT USE!!!!!
   */
  public void stall() {
    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
  }  
  
  /**
   *        ----- INTERNAL METHODS -----
   */
  
  /**
   * Builds the data for a short ping
   */
//  protected void sendShortPing(SourceRoute route) {
//    try {
////      ByteArrayOutputStream baos = new ByteArrayOutputStream();
////      DataOutputStream dos = new DataOutputStream(baos);
//      
//      SocketBuffer sb = new SocketBuffer(localAddress, route);
//      OutputBuffer dos = sb.o;
//      
//      dos.write(HEADER_SHORT_PING, 0, HEADER_SHORT_PING.length);
//      dos.writeLong(environment.getTimeSource().currentTimeMillis());
//      
//      sb.setType(SHORT_PING_TYPE);
//      
//      enqueue(route, sb);
//    } catch (Exception canthappen) {
//      if (logger.level <= Logger.SEVERE) logger.logException(
//          "CANT HAPPEN: ",canthappen);  
//    }
//  }
  
  /**
   * Builds the data for a short ping response
   */
//  protected void shortPingReceived(SourceRoute from, byte[] payload) throws IOException {
//    SourceRoute route = from.reverse();
//    SocketBuffer sb = new SocketBuffer(localAddress, route);
//    System.arraycopy(HEADER_SHORT_PING_RESPONSE, 0, payload, 0, HEADER_SHORT_PING_RESPONSE.length);
//    sb.o.write(payload,0,payload.length);
//    sb.setType(SHORT_PING_RESPONSE_TYPE);
//    enqueue(route, sb);
//  }
  
  /**
   * Processes a short ping response
   */
//  protected void shortPingResponseReceived(SourceRoute route, byte[] payload) throws IOException {
//    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
//    dis.readFully(new byte[HEADER_SHORT_PING_RESPONSE.length]);
//    long start = dis.readLong();
//    int ping = (int) (environment.getTimeSource().currentTimeMillis() - start);
//
//    SourceRoute from = route.reverse();
//    
//    manager.markAlive(from);
//    manager.markProximity(from, ping);
//    notifyPingResponseListeners(from, ping, start);
//  }

  /**
   * Adds a feature to the PingResponseListener attribute of the PingManager
   * object
   *
   * @param address The feature to be added to the PingResponseListener
   *      attribute
   * @param prl The feature to be added to the PingResponseListener attribute
   */
  protected void removePingResponseListener(SourceRoute path, PingResponseListener prl) {
    if (prl == null) 
      return;
    
    synchronized(pingListeners) {
      ArrayList list = (ArrayList) pingListeners.get(path);
      
      if (list != null) {
        // remove all
        while(list.remove(prl));
      }
    }
  }
  
  /**
   * Adds a feature to the PingResponseListener attribute of the PingManager
   * object
   *
   * @param address The feature to be added to the PingResponseListener
   *      attribute
   * @param prl The feature to be added to the PingResponseListener attribute
   */
  protected void addPingResponseListener(SourceRoute path, PingResponseListener prl) {
    if (prl == null) 
      return;
    
    synchronized(pingListeners) {
      ArrayList list = (ArrayList) pingListeners.get(path);
      
      if (list == null) {
        list = new ArrayList();
        pingListeners.put(path, list);
      }
      
      list.add(prl);
    }
  }
  
  /**
   * caller must synchronized(pingResponseTimes)
   *
   * @param address
   * @param proximity
   * @param lastTimePinged
   */
  protected void notifyPingResponseListeners(SourceRoute path, int proximity, long lastTimePinged) {
    ArrayList list;
//    System.out.println("PM.notifyPingResponseListeners("+path+"("+path.hashCode()+"),"+proximity+")");
    synchronized(pingListeners) {
      list = (ArrayList) pingListeners.remove(path);
    }
    
    if (list != null) {
      Iterator i = list.iterator();
      
      while (i.hasNext()) 
        ((PingResponseListener) i.next()).pingResponse(path, proximity, lastTimePinged);
    }
  }

  static class PMDeserializer implements MessageDeserializer {

    Logger logger;
    public PMDeserializer(Logger logger) {
      this.logger = logger; 
    }
    
    public rice.p2p.commonapi.Message deserialize(InputBuffer buf, short type, int priority, rice.p2p.commonapi.NodeHandle sender) throws IOException {
      switch (type) {        
        case IPAddressRequestMessage.TYPE:          
          return new IPAddressRequestMessage(buf);          
        case IPAddressResponseMessage.TYPE:          
          return new IPAddressResponseMessage(buf);          
        case PingMessage.TYPE:
          return new PingMessage(buf);          
        case PingResponseMessage.TYPE:          
          return new PingResponseMessage(buf);          
        case WrongEpochMessage.TYPE:            
          return new WrongEpochMessage(buf);          
                        
        default:
          if (logger.level <= Logger.SEVERE) logger.logException( "PM SERIOUS ERROR: Received unknown message address: "+0+" type:"+type,new Exception("stack trace"));
      }
      return null;
    }    
  }
  
  MessageDeserializer deserializer;

  public void enqueue(SourceRoute path, PRawMessage msg) {
    if (logger.level <= Logger.FINER-3) logger.log("enqueue("+path+","+msg+")"); 
    try {
      enqueue(path, new SocketBuffer(localAddress,path,msg));
    } catch (IOException e) {
      if (logger.level <= Logger.SEVERE) logger.log(
          "ERROR: Received exceptoin " + e + " while enqueuing ping " + msg);
    }
  }
  
  
  /**
   * DESCRIBE THE METHOD
   *
   * @param address DESCRIBE THE PARAMETER
   * @param msg DESCRIBE THE PARAMETER
   */
  public void enqueue(SourceRoute path, SocketBuffer msg) {
//      SocketBuffer data = addHeader(path, msg, localAddress, environment,logger);
      
      synchronized (pendingMsgs) {
        pendingMsgs.add(new Envelope(path.getFirstHop(), msg));
      }
      
      if (spn != null)
        ((SocketPastryNode) spn).broadcastSentListeners(msg.getInnermostAddress(), msg.getInnermostType(), path.getLastHop().getAddress(localAddress), msg.getBuffer().limit(), NetworkListener.TYPE_UDP);
      
//      if (logger.level <= Logger.FINER) {
//        switch (msg.getType()) {
//  //      if (! (msg instanceof byte[])) {
//          case SHORT_PING_TYPE:
//            logger.log("COUNT: Sent message rice.pastry.socket.messaging.ShortPingMessage of size " + msg.getBuffer().limit()  + " to " + path);                
//            break;
//          case SHORT_PING_RESPONSE_TYPE:
//            logger.log("COUNT: Sent message rice.pastry.socket.messaging.ShortPingResponseMessage of size " + msg.getBuffer().limit()  + " to " + path);           
//            break;
//          default:
            if (logger.level <= Logger.FINER-3) logger.log(
              "COUNT: Sent message " + msg.getInnermostAddress()+":"+msg.getInnermostType() + " of size " + msg.getBuffer().limit()  + " to " + path);    
//        }
//      }        
      environment.getSelectorManager().modifyKey(key);
  }

  /**
   * DESCRIBE THE METHOD
   *
   * @param message DESCRIBE THE PARAMETER
   * @param address DESCRIBE THE PARAMETER
   */
  public void receiveMessage(SourceRoute sr, DatagramMessage dm, int size, SourceRoute fromPath) throws IOException {
//    if (message instanceof DatagramMessage) {
//      DatagramMessage dm = (DatagramMessage) message;      
      long start = dm.getStartTime();
      SourceRoute inboundPath = sr.removeLastHop(); //dm.getInboundPath();
      SourceRoute outboundPath = inboundPath.reverse(); //dm.getOutboundPath();
      
//      if (inboundPath == null)
//        inboundPath = SourceRoute.build(new EpochInetSocketAddress(from));

      if (spn != null)
        ((SocketPastryNode) spn).broadcastReceivedListeners(dm.getDestination(), dm.getType(), outboundPath.getLastHop().getAddress(localAddress), size, NetworkListener.TYPE_UDP);
            
      if (dm instanceof PingMessage) {
        if (logger.level <= Logger.FINE) {
          logger.log(
              "(PM) Sending PingResponse["+start+"] via path " + outboundPath + " local " + localAddress +" sr:"+sr+" ib:"+inboundPath);
        } else 
          if (logger.level <= Logger.FINER) logger.log(
            "COUNT: Read message(1) " + dm.getClass() + " of size " + size + " from " + outboundPath); //inboundPath.reverse());      

//        enqueue(inboundPath.reverse(), new PingResponseMessage(/*outboundPath, inboundPath, */start));        
        enqueue(outboundPath, new PingResponseMessage(start));        
      } else if (dm instanceof PingResponseMessage) {
        int ping = (int) (environment.getTimeSource().currentTimeMillis() - start);

        if (logger.level <= Logger.FINE) logger.log(
            "COUNT: Read PingResponse["+start+"]:RTT="+ping+" of size " + size + " from " + inboundPath);      
        if ((ping >= 0) && (ping < MIN_RTT)) {
          if (inboundPath.getFirstHop() == localAddress) {
            if (logger.level <= Logger.FINER) logger.log("pinged self"+inboundPath.getFirstHop());            
          } else {
            if (logger.level <= Logger.FINER) logger.log("RTT from "+inboundPath+" was "+ping+" setting to "+MIN_RTT);            
            ping = MIN_RTT; // cant have a zero latency connection 
          }
        }
        if (ping > 0) {
          manager.markAlive(outboundPath);
          manager.markProximity(outboundPath, ping);
          notifyPingResponseListeners(outboundPath, ping, start);
        } else {
          if (logger.level <= Logger.WARNING) logger.log(
              "COUNT: Read PingResponse["+start+"]:RTT="+ping+"!!! of size " + size + " from " + inboundPath);                
        }
      } else if (dm instanceof WrongEpochMessage) {
        WrongEpochMessage wem = (WrongEpochMessage) dm;
        
        if (logger.level <= Logger.FINER-5) logger.log(
            "COUNT: Read message(3) " + dm.getClass() + " of size " + size + " from " + outboundPath.reverse());      

        manager.markAlive(outboundPath);
        manager.markDead(wem.getIncorrect());
      } else if (dm instanceof IPAddressRequestMessage) {
        if (logger.level <= Logger.FINER-5) logger.log(
            "COUNT: Read message(4) " + dm.getClass() + " of size " + size + " from " + fromPath);      
        
        enqueue(fromPath, new IPAddressResponseMessage(fromPath.path[0].getAddress(localAddress), environment.getTimeSource().currentTimeMillis())); 
      } else {
        if (logger.level <= Logger.WARNING) logger.log(
            "ERROR: Received unknown DatagramMessage " + dm);
      }
//    }
  }
  
  /**
   * DESCRIBE THE METHOD
   *
   * @param key DESCRIBE THE PARAMETER
   */
  public void read(SelectionKey key) {
    try {
      InetSocketAddress address = null;
      
      while ((address = (InetSocketAddress) channel.receive(buffer)) != null) {
        buffer.flip();

        if (testSourceRouting) {
          if (address.getPort() % 2 == localAddress.getAddress(localAddress).getPort() % 2) {
//          if ((address.getPort() % 2 == 0) && (localAddress.getAddress().getPort() % 2 == 0)) {
            buffer.clear();
            if (logger.level <= Logger.INFO) logger.log("Dropping packet from"+address);
            return;
          } 
        }
        
        if (buffer.remaining() > 0) {
          readHeader(address);
        } else {
          if (logger.level <= Logger.INFO) logger.log(
            "(PM) Read from datagram channel, but no bytes were there - no bad, but wierd.");
          break;
        }
      }
    } catch (IOException e) {
      if (logger.level <= Logger.WARNING) logger.logException(
          "ERROR (datagrammanager:read): ", e);
    } finally {
      buffer.clear();
    }
  }

  /**
   * DESCRIBE THE METHOD
   *
   * @param key DESCRIBE THE PARAMETER
   */
  public void write(SelectionKey key) {
    Envelope write = null;
    
    try {
      synchronized (pendingMsgs) {
        Iterator i = pendingMsgs.iterator();

        while (i.hasNext()) {
          write = (Envelope) i.next();
          
          if (logger.level <= Logger.FINEST) {
            write.log("PM.write to "+write.destination.getAddress(localAddress));
          }
          if (write.data.getBuffer().get(HEADER_SIZE) != 0) {
            throw new IOException("Attempting to send Invalid version");
          }
          try {            
            if (channel.send(write.data.getBuffer(), write.destination.getAddress(localAddress)) == write.data.getBuffer().limit())
              i.remove();
            else 
              break;
          } catch (IOException e) {
            i.remove();
            throw e;
          }
        }
      }
    } catch (IOException e) {
      if (logger.level <= Logger.WARNING) {
        // This code prevents this line from filling up logs during some kinds of network outages
        // it makes this error only be printed 1ce/second
        long now = timeSource.currentTimeMillis();
        if (lastTimePrinted+1000 > now) return;
        lastTimePrinted = now;
        
        logger.logException(
          "ERROR (datagrammanager:write) to " + (write == null ? null : write.destination.getAddress(localAddress)), e);
      }        
    } finally {
      if (pendingMsgs.isEmpty()) 
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }
  }

  long lastTimePrinted = 0;
  
  /**
   * DESCRIBE THE METHOD
   *
   * @param key DESCRIBE THE PARAMETER
   */
  public void modifyKey(SelectionKey key) {
    synchronized (pendingMsgs) {
      if (! pendingMsgs.isEmpty()) 
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }
  }
  
  /**
   * Method which serializes a given object into a ByteBuffer, in order to
   * prepare it for writing.
   *
   * @param o The object to serialize
   * @return A ByteBuffer containing the object
   * @exception IOException if the object can't be serialized
   */
//  public static byte[] serialize(Object message, Environment environment, Logger logger) throws IOException {
//    try {
//      
//      ByteArrayOutputStream baos = new ByteArrayOutputStream();
//      ObjectOutputStream oos = new ObjectOutputStream(baos);      
//      oos.writeObject(message);
//      oos.close();
//      
//      byte[] ret = baos.toByteArray();
//      return ret;
//    } catch (InvalidClassException e) {
//      if (logger.level <= Logger.SEVERE) logger.logException(
//          "PANIC: Object to be serialized was an invalid class!",e);
//      throw new IOException("Invalid class during attempt to serialize.");
//    } catch (NotSerializableException e) {
//      if (logger.level <= Logger.SEVERE) logger.logException(
//          "PANIC: Object to be serialized was not serializable! [" + message + "]",e);
//      throw new IOException("Unserializable class " + message + " during attempt to serialize.");
//    }
//  }
  
  /**
   * Method which takes in a ByteBuffer read from a datagram, and deserializes
   * the contained object.
   *
   * @param buffer The buffer read from the datagram.
   * @return The deserialized object.
   * @exception IOException if the buffer can't be deserialized
   */
//  public static Object deserialize(byte[] array, Environment env, SocketPastryNode spn, Logger logger) throws IOException {
//    PastryObjectInputStream ois = new PastryObjectInputStream(new ByteArrayInputStream(array), spn);
//    
//    try {
//      Object ret = ois.readObject();
//      return ret;      
//    } catch (ClassNotFoundException e) {
//      if (logger.level <= Logger.SEVERE) logger.logException(
//          "PANIC: Unknown class type in serialized message!",e);
//      throw new IOException("Unknown class type in message - closing channel.");
//    } catch (InvalidClassException e) {
//      if (logger.level <= Logger.SEVERE) logger.logException(
//          "PANIC: Serialized message was an invalid class!",e);
//      throw new IOException("Invalid class in message - closing channel.");
//    }
//  }
  /**
   * Method which adds a header for the provided path to the given data.
   *
   * @return The messag with a header attached
   */
//  public static SocketBuffer addHeader(SourceRoute path, SocketBuffer data, EpochInetSocketAddress localAddress, Environment env, Logger logger) throws IOException {
////    ByteArrayOutputStream baos = new ByteArrayOutputStream();
////    DataOutputStream dos = new DataOutputStream(baos);      
//
//    SocketBuffer sb = new SocketBuffer();
//    OutputBuffer dos = sb.o;
//    
//    dos.write(HEADER_PING, 0, HEADER_PING.length);
//    dos.writeByte((byte) 1);
//    dos.writeByte((byte) (path.getNumHops() + 1));
//    SocketChannelRepeater.encodeHeader(localAddress, dos);
//    
//    for (int i=0; i<path.getNumHops(); i++) 
//      SocketChannelRepeater.encodeHeader(path.getHop(i), dos);
//
//    dos.write(data.buffer.array(), 0, data.buffer.limit());
//    
////    dos.flush();
//  
////    return new SocketBuffer(baos.toByteArray());
//    return sb;
//  }
  
  /**
   * Method which adds a header for the provided path to the given data.
   *
   * @return The messag with a header attached
   */
  public SourceRoute decodeHeader(byte[] header, int numAddresses) throws IOException {
    EpochInetSocketAddress[] route = new EpochInetSocketAddress[numAddresses];
  
    for (int i=0; i<route.length; i++)
      route[i] = SocketChannelRepeater.decodeHeader(header, i);
    
    return SourceRoute.build(route);
  }  
  
  /**
   * Method which processes an incoming message and hands it off to the appropriate
   * handler.
   */
  protected void readHeader(InetSocketAddress address) throws IOException {
    if (buffer.remaining() < HEADER_SIZE) {
      throw new IOException("Not a pastry message from "+address+": message size:"+buffer.remaining());
    }
    byte[] header = new byte[HEADER_SIZE];
    buffer.get(header, 0, HEADER_SIZE);
    if (!Arrays.equals(header, SocketCollectionManager.PASTRY_MAGIC_NUMBER)) throw new IOException("Not a pastry message from "+address+":"+header[0]+","+header[1]+","+header[2]+","+header[3]);
    
    buffer.get(header, 0, HEADER_SIZE);
    int version = MathUtils.byteArrayToInt(header);
    if (!(version == 0)) 
      throw new IOException("Unknown Version:"+version);
    
//    header = new byte[HEADER_SIZE];
//    buffer.get(header);
//    
//    if (Arrays.equals(header, HEADER_PING)) {
      byte[] metadata = new byte[2];
      buffer.get(metadata);
      short headerLength = buffer.getShort();
      
//      System.out.println("PM.readHeader() headerLength:"+headerLength);
      
      // first, read all of the source route
      byte[] route = new byte[headerLength];
      buffer.get(route);
            
      // now, check to make sure our hop is correct
      EpochInetSocketAddress eisa;
      
      if (logger.level <= Logger.FINEST) {
        logger.log("readHeader("+address+") ("+metadata[0]+" "+metadata[1]+") local "+localAddress); 
        for (int i = 0; i < metadata[1]; i++) {
          logger.log("  "+SocketChannelRepeater.decodeHeader(route, i));           
        }
      }
              
      try {
        eisa = SocketChannelRepeater.decodeHeader(route, metadata[0]);
      } catch (IOException ioe) {
        throw ioe; 
      }
      
      SourceRoute fromPath = SourceRoute.build(new EpochInetSocketAddress(address));

      
      // if so, process the packet
      if ((eisa.equals(localAddress)) || (eisa.getAddress(localAddress).equals(localAddress.getAddress(localAddress)) &&
                                          (eisa.getEpoch() == EpochInetSocketAddress.EPOCH_UNKNOWN))) {
        // if the packet is at the end of the route, accept it
        // otherwise, forward it to the next hop (and increment the stamp)
        if (metadata[0] + 1 == metadata[1]) {
          // The message was meant for me
          byte[] array = new byte[buffer.remaining()];
          buffer.get(array);
          buffer.clear();
          
//          byte[] test = new byte[HEADER_SHORT_PING.length];
//          System.arraycopy(array, 0, test, 0, test.length);
          
          SourceRoute inbound = decodeHeader(route, metadata[1]);
//          SourceRoute sr = inbound.removeLastHop();
          
//          if (Arrays.equals(test, HEADER_SHORT_PING)) {
//            // the PING was meant for me
//            int len = (header.length + metadata.length + array.length + route.length);
//            if (logger.level <= Logger.FINER) logger.log(                  
//                "COUNT: Read message rice.pastry.socket.messaging.ShortPingMessage of size " + len  + " from " + sr);    
//            if (spn != null) {
//              ((SocketPastryNode) spn).broadcastReceivedListeners(array, address, len, NetworkListener.TYPE_UDP);
//            }            
//            
//            shortPingReceived(sr, array);
//          } else if (Arrays.equals(test, HEADER_SHORT_PING_RESPONSE)) {
//            // the PING_RESPONSE was meant for me
//            int len = (header.length + metadata.length + array.length + route.length);
//            if (logger.level <= Logger.FINER) logger.log(
//                "COUNT: Read message rice.pastry.socket.messaging.ShortPingResponseMessage of size " + len  + " from " + sr);    
//            
//            if (spn != null) {
//              ((SocketPastryNode) spn).broadcastReceivedListeners(array, address, len, NetworkListener.TYPE_UDP);
//            }            
//            shortPingResponseReceived(sr, array);
//          } else {
            // a normal message
            SocketBuffer delivery = new SocketBuffer(array,spn);
            receiveMessage(inbound, (DatagramMessage)delivery.deserialize(deserializer), array.length, fromPath);
//          }
        } else {
          // sourceroute hop
          EpochInetSocketAddress next = SocketChannelRepeater.decodeHeader(route, metadata[0] + 1);
          buffer.position(0);
          byte[] packet = new byte[buffer.remaining()];
          buffer.get(packet);
          
          // increment the hop count
          packet[HEADER_SIZE+4]++;
          
          if (logger.level <= Logger.FINE) logger.log("Forwarding ("+metadata[0]+" "+metadata[1]+") from "+address+" to "+next+" at "+localAddress);

          if (spn != null) {
            ((SocketPastryNode) spn).broadcastReceivedListeners(0,(short)0, address, packet.length, NetworkListener.TYPE_SR_UDP);
            ((SocketPastryNode) spn).broadcastSentListeners(0,(short)0, next.getAddress(localAddress), packet.length, NetworkListener.TYPE_SR_UDP);          
          }
          
          synchronized (pendingMsgs) {
            pendingMsgs.add(new Envelope(next, new SocketBuffer(packet)));
          }
          
          environment.getSelectorManager().modifyKey(key);
        }
      } else {
        // if this is an old epoch of ours, reply with an update
        if (eisa.addressEquals(localAddress)) {
          SourceRoute back = SourceRoute.build(new EpochInetSocketAddress[0]);
          SourceRoute outbound = SourceRoute.build(new EpochInetSocketAddress[0]);
          
          for (int i=0; i<metadata[0]; i++) {
            back = back.append(SocketChannelRepeater.decodeHeader(route, i));
            if (i > 0)
              outbound = outbound.append(SocketChannelRepeater.decodeHeader(route, i));
          }
          
          outbound = outbound.append(localAddress);
//          if (spn != null) {
//            ((SocketPastryNode) spn).broadcastReceivedListeners(packet, address, packet.length, NetworkListener.TYPE_SR_UDP);
//          }
          
          WrongEpochMessage wem = new WrongEpochMessage(/*outbound, back.reverse(), */eisa, localAddress, environment.getTimeSource().currentTimeMillis());

          if (spn != null) {
            ((SocketPastryNode) spn).broadcastReceivedListeners(0,(short)0, address, buffer.remaining(), NetworkListener.TYPE_UDP);
          }
          
          enqueue(back.reverse(), wem);
        } else {
          if (logger.level <= Logger.WARNING) logger.log(
              "WARNING: Received packet destined for EISA (" + metadata[0] + " " + metadata[1] + ") " + eisa + " but the local address is " + localAddress + " - dropping silently.");
          throw new IOException("Received packet destined for EISA (" + metadata[0] + " " + metadata[1] + ") " + eisa + " but the local address is " + localAddress + " - dropping silently.");
        }
      }
//    } else {
//      if (logger.level <= Logger.WARNING) logger.log(
//        "WARNING: Received unrecognized message header - ignoring from "+address+".");
//      throw new IOException("Improper message header received - ignoring from "+address+". Read " + ((byte) header[0]) + " " + ((byte) header[1]) + " " + ((byte) header[2]) + " " + ((byte) header[3]));
//    }    
  }
  
  /**
   * Internal class which holds a pending datagram
   *
   * @author amislove
   */
  public class Envelope {
    protected EpochInetSocketAddress destination;
    protected SocketBuffer data;

    public void log(String callingFrom) {      
      try {
        byte[] metadata = new byte[2];
        metadata[0] = data.getBuffer().get(HEADER_SIZE+4);
        metadata[1] = data.getBuffer().get(HEADER_SIZE+5);
        short routeLength = data.getBuffer().getShort(HEADER_SIZE+6);
        byte[] route = new byte[routeLength];
        System.arraycopy(data.getBuffer().array(), HEADER_SIZE+8, route, 0, route.length);
        logger.log("log(<"+callingFrom+">"+destination+") ("+metadata[0]+" "+metadata[1]+") local "+localAddress); 
        EpochInetSocketAddress[] fullHeader = SocketChannelRepeater.decodeFullHeader(route, metadata[1]);
        for (int ii = 0; ii < metadata[1]; ii++) {
          logger.log("  "+fullHeader[ii]);           
        }      
      } catch (IOException ioe) {
        logger.logException("",ioe); 
      }
    }
    
    /**
     * Constructor for Envelope.
     *
     * @param adr DESCRIBE THE PARAMETER
     * @param m DESCRIBE THE PARAMETER
     */
    public Envelope(EpochInetSocketAddress destination, SocketBuffer data) {
      this.destination = destination;
      this.data = data;
      
      if (logger.level <= Logger.FINEST) {
        log("Env.ctor");
      }
//      if (logger.level <= Logger.FINEST) {
//          byte[] metadata = new byte[2];
//          metadata[0] = data.getBuffer().get(HEADER_SIZE+4);
//          metadata[1] = data.getBuffer().get(HEADER_SIZE+5);
////          short headerLength = data.get
//          byte[] route = new byte[SocketChannelRepeater.HEADER_BUFFER_SIZE * metadata[1]];
//          System.arraycopy(data.getBuffer().array(), HEADER_SIZE+8, route, 0, route.length);
//          logger.log("enqueue("+destination+") ("+metadata[0]+" "+metadata[1]+") local "+localAddress); 
//          for (int ii = 0; ii < metadata[1]; ii++) {
//            logger.log("  "+SocketChannelRepeater.decodeHeader(route, ii));           
//          }
//
////        if (metadata[1] == 3 && metadata[0] == 1) {
////          EpochInetSocketAddress dest = SocketChannelRepeater.decodeHeader(route, metadata[1]-1);
////          if (dest.equals(destination)) {
////            System.out.println("Warning");
////          }                
////        }
//        } catch (IOException ioe) {
//          logger.logException("",ioe); 
//        }
//      }
      if (data.getBuffer().get(HEADER_SIZE) != 0) {
        throw new RuntimeException("Attempting to send Invalid version");
      }      
    }
  }  
}
