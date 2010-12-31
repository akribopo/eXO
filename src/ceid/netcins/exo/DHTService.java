package ceid.netcins.exo;

/**
 *
 * @author <a href="mailto:loupasak@ceid.upatras.gr">Andreas Loupasakis</a>
 * @author <a href="mailto:ntarmos@cs.uoi.gr">Nikos Ntarmos</a>
 * @author <a href="mailto:peter@ceid.upatras.gr">Peter Triantafillou</a>
 * 
 * "eXO: Decentralized Autonomous Scalable Social Networking"
 * Proc. 5th Biennial Conf. on Innovative Data Systems Research (CIDR),
 * January 9-12, 2011, Asilomar, California, USA.
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.WeakHashMap;

import rice.Continuation;
import rice.Continuation.ListenerContinuation;
import rice.Continuation.NamedContinuation;
import rice.Continuation.StandardContinuation;
import rice.environment.Environment;
import rice.environment.logging.Logger;
import rice.environment.params.Parameters;
import rice.p2p.commonapi.Application;
import rice.p2p.commonapi.CancellableTask;
import rice.p2p.commonapi.Endpoint;
import rice.p2p.commonapi.Id;
import rice.p2p.commonapi.IdFactory;
import rice.p2p.commonapi.IdRange;
import rice.p2p.commonapi.IdSet;
import rice.p2p.commonapi.Message;
import rice.p2p.commonapi.Node;
import rice.p2p.commonapi.NodeHandle;
import rice.p2p.commonapi.NodeHandleSet;
import rice.p2p.commonapi.RouteMessage;
import rice.p2p.commonapi.appsocket.AppSocket;
import rice.p2p.commonapi.appsocket.AppSocketReceiver;
import rice.p2p.commonapi.rawserialization.InputBuffer;
import rice.p2p.commonapi.rawserialization.MessageDeserializer;
import rice.p2p.past.Past;
import rice.p2p.past.PastContent;
import rice.p2p.past.PastContentHandle;
import rice.p2p.past.PastException;
import rice.p2p.past.PastPolicy;
import rice.p2p.past.PastPolicy.DefaultPastPolicy;
import rice.p2p.past.messaging.CacheMessage;
import rice.p2p.past.messaging.ContinuationMessage;
import rice.p2p.past.messaging.FetchHandleMessage;
import rice.p2p.past.messaging.FetchMessage;
import rice.p2p.past.messaging.InsertMessage;
import rice.p2p.past.messaging.LookupHandlesMessage;
import rice.p2p.past.messaging.LookupMessage;
import rice.p2p.past.messaging.MessageLostMessage;
import rice.p2p.past.messaging.PastMessage;
import rice.p2p.past.rawserialization.DefaultSocketStrategy;
import rice.p2p.past.rawserialization.JavaPastContentDeserializer;
import rice.p2p.past.rawserialization.JavaPastContentHandleDeserializer;
import rice.p2p.past.rawserialization.PastContentDeserializer;
import rice.p2p.past.rawserialization.PastContentHandleDeserializer;
import rice.p2p.past.rawserialization.SocketStrategy;
import rice.p2p.replication.Replication;
import rice.p2p.replication.manager.ReplicationManager;
import rice.p2p.replication.manager.ReplicationManagerClient;
import rice.p2p.replication.manager.ReplicationManagerImpl;
import rice.p2p.util.MathUtils;
import rice.p2p.util.rawserialization.SimpleInputBuffer;
import rice.persistence.LockManager;
import rice.persistence.LockManagerImpl;
import rice.persistence.StorageManager;
import ceid.netcins.exo.messages.FriendAcceptMessage;
import ceid.netcins.exo.messages.FriendQueryMessage;
import ceid.netcins.exo.messages.FriendRejectMessage;
import ceid.netcins.exo.messages.FriendReqMessage;
import ceid.netcins.exo.messages.FriendReqPDU;
import ceid.netcins.exo.messages.GetUserProfileMessage;
import ceid.netcins.exo.messages.MessageType;
import ceid.netcins.exo.messages.QueryMessage;
import ceid.netcins.exo.messages.QueryPDU;
import ceid.netcins.exo.messages.ResponsePDU;
import ceid.netcins.exo.messages.RetrieveContIDsMessage;
import ceid.netcins.exo.messages.RetrieveContMessage;
import ceid.netcins.exo.messages.RetrieveContPDU;
import ceid.netcins.exo.messages.RetrieveContTagsMessage;
import ceid.netcins.exo.messages.SocialQueryMessage;
import ceid.netcins.exo.messages.SocialQueryPDU;
import ceid.netcins.exo.messages.TagContentMessage;
import ceid.netcins.exo.messages.TagPDU;
import ceid.netcins.exo.messages.TagUserMessage;
import ceid.netcins.exo.similarity.Scorer;
import ceid.netcins.exo.similarity.SimilarityRequest;

/**
 * 
 * 
 * @(#) DHTService.java
 * 
 *      This is an implementation of the Past interface.
 */
@SuppressWarnings("unchecked")
public class DHTService implements Past, Application, ReplicationManagerClient {

	// ----- STATIC FIELDS -----
	// the number of milliseconds to wait before declaring a message lost
	public final int MESSAGE_TIMEOUT;// = 30000;

	// the percentage of successful replica inserts in order to declare success
	public final double SUCCESSFUL_INSERT_THRESHOLD;// = 0.5;

	// ----- VARIABLE FIELDS -----

	// this application's endpoint
	protected Endpoint endpoint;

	// the storage manager used by this Past
	protected StorageManager storage;

	// the replication factor for Past
	protected int replicationFactor;

	// the replica manager used by Past
	protected ReplicationManager replicaManager;

	protected LockManager lockManager;

	// the policy used for application-specific behavior
	protected PastPolicy policy;

	// the unique ids used by the messages sent across the wire
	private int id;

	// the hashtable of outstanding messages
	@SuppressWarnings("rawtypes")
	private Hashtable<Integer, Continuation> outstanding;

	// the hashtable of outstanding timer tasks
	private Hashtable<Integer, CancellableTask> timers;

	// the factory for manipulating ids
	protected IdFactory factory;

	// the instance name we are running with
	protected String instance;

	// debug variables
	public int inserts = 0;
	public int lookups = 0;
	public int fetchHandles = 0;
	public int other = 0;

	protected Environment environment;
	protected Logger logger;

	protected PastContentDeserializer contentDeserializer;
	protected PastContentHandleDeserializer contentHandleDeserializer;

	public SocketStrategy socketStrategy;

	// Our Scorer thread functionality
	public Scorer scorer;
	public Thread scorerThread;

	// Load counting variable
	public int hits;

	protected class PastDeserializer implements MessageDeserializer {
		public Message deserialize(InputBuffer buf, short type, int priority,
				NodeHandle sender) throws IOException {
			try {
				switch (type) {
				case MessageType.Cache:
					return CacheMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.FetchHandle:
					return FetchHandleMessage.build(buf, endpoint,
							contentHandleDeserializer);
				case MessageType.Fetch:
					return FetchMessage.build(buf, endpoint,
							contentDeserializer, contentHandleDeserializer);
				case MessageType.Insert:
					return InsertMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.LookupHandles:
					return LookupHandlesMessage.build(buf, endpoint);
				case MessageType.Lookup:
					return LookupMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.Query:
					return QueryMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.FriendRequest:
					return FriendReqMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.FriendAccept:
					return FriendAcceptMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.FriendReject:
					return FriendRejectMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.TagContent:
					return TagContentMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.TagUser:
					return TagUserMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.SocialQuery:
					return SocialQueryMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.RetrieveContent:
					return RetrieveContMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.RetrieveContentTags:
					return RetrieveContTagsMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.RetrieveContentIDs:
					return RetrieveContIDsMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.FriendQuery:
					return FriendQueryMessage.build(buf, endpoint,
							contentDeserializer);
				case MessageType.GetUserProfile:
					return GetUserProfileMessage.build(buf, endpoint,
							contentDeserializer);
				}
			} catch (IOException e) {
				if (logger.level <= Logger.SEVERE)
					logger.log("Exception in deserializer in "
							+ DHTService.this.endpoint.toString() + ":"
							+ instance + " " + e);
				throw e;
			}
			throw new IllegalArgumentException("Unknown type:" + type + " in "
					+ DHTService.this.toString());
		}
	}

	/**
	 * Constructor for Past
	 * 
	 * @param node
	 *            The node below this Past implementation
	 * @param manager
	 *            The storage manager to be used by Past
	 * @param replicas
	 *            The number of object replicas
	 * @param instance
	 *            The unique instance name of this Past
	 */
	@SuppressWarnings("rawtypes")
	public DHTService(Node node, StorageManager manager,
			int replicas, String instance) {
		this.environment = node.getEnvironment();
		logger = environment.getLogManager().getLogger(getClass(), instance);
		Parameters p = environment.getParameters();
		MESSAGE_TIMEOUT = p.getInt("p2p_past_messageTimeout");// = 30000;
		SUCCESSFUL_INSERT_THRESHOLD = p
				.getDouble("p2p_past_successfulInsertThreshold");// = 0.5;
		this.socketStrategy = new DefaultSocketStrategy(false);
		this.storage = manager;
		this.contentDeserializer = new JavaPastContentDeserializer();
		this.contentHandleDeserializer = new JavaPastContentHandleDeserializer();
		this.endpoint = node.buildEndpoint(this, instance);
		this.endpoint.setDeserializer(new PastDeserializer());
		this.factory = node.getIdFactory();
		this.policy = new DefaultPastPolicy();
		this.instance = instance;
		this.hits = 0;

		this.id = Integer.MIN_VALUE;
		this.outstanding = new Hashtable<Integer, Continuation>();
		this.timers = new Hashtable<Integer, CancellableTask>();
		this.replicationFactor = replicas;

		this.replicaManager = buildReplicationManager(node, instance);

		this.lockManager = new LockManagerImpl(environment);

		this.endpoint.accept(new AppSocketReceiver() {

			public void receiveSocket(AppSocket socket) {
				if (logger.level <= Logger.FINE)
					logger.log("Received Socket from " + socket);
				socket.register(true, false, 10000, this);
				endpoint.accept(this);
			}

			public void receiveSelectResult(AppSocket socket, boolean canRead,
					boolean canWrite) {
				if (logger.level <= Logger.FINER)
					logger.log("Reading from " + socket);
				try {
					ByteBuffer[] bb = (ByteBuffer[]) pendingSocketTransactions
							.get(socket);
					if (bb == null) {
						// this is a new message

						// read the size
						byte[] sizeArr = new byte[4];
						for (int i = 0; i < sizeArr.length; i++) {
							bb = new ByteBuffer[1];
							bb[0] = ByteBuffer.allocate(1);
							if (socket.read(bb[0]) == -1) {
								close(socket);
								return;
							}
							sizeArr[i] = bb[0].get(0);
						}
						int size = MathUtils.byteArrayToInt(sizeArr);

						if (logger.level <= Logger.FINER)
							logger.log("Found object of size " + size
									+ " from " + socket);

						// allocate a buffer to store the object, save it in the
						// pst
						bb[0] = ByteBuffer.allocate(size);
						pendingSocketTransactions.put(socket, bb);
					}

					// now we have a bb

					// read some bytes
					// XXX: This used to do sort-of scatter-gather, only with just one ByteBuffer object
					if (socket.read(bb[0]) == -1) {
						close(socket);
					}

					// deserialize or reregister
					if (bb[0].remaining() == 0) {
						// make sure to clear things up so we can keep receiving
						pendingSocketTransactions.remove(socket);

						if (logger.level <= Logger.FINEST)
							logger.log("bb[0].limit() " + bb[0].limit()
									+ " bb[0].remaining() " + bb[0].remaining()
									+ " from " + socket);

						// deserialize the object
						SimpleInputBuffer sib = new SimpleInputBuffer(bb[0]
								.array());

						short type = sib.readShort();

						PastMessage result = (PastMessage) endpoint
								.getDeserializer().deserialize(sib, type,
										(byte) 0, null);
						deliver(null, result);

					}

					// there will be more data on the socket if we haven't
					// received everything yet
					// need to register either way to be able to read from the
					// sockets when they are closed remotely, could
					// alternatively close early
					// cause we are currently only sending 1 message per socket,
					// but it's better to just keep reading in case we one day
					// reuse sockets
					socket.register(true, false, 10000, this);

					// recursive call to handle next object
					// cant do this becasue calling read when not ready throws
					// an exception
					// receiveSelectResult(socket, canRead, canWrite);
				} catch (IOException ioe) {
					receiveException(socket, ioe);
				}
			}

			public void receiveException(AppSocket socket, Exception e) {
				if (logger.level <= Logger.WARNING)
					logger.logException("Error receiving message", e);
				close(socket);
			}

			public void close(AppSocket socket) {
				if (socket == null)
					return;
				// System.out.println("Closing "+socket);
				pendingSocketTransactions.remove(socket);
				socket.close();
			}

		});
		endpoint.register();
	}

	public String toString() {
		if (endpoint == null)
			return super.toString();
		return "DHTService[" + endpoint.getInstance() + "]";
	}

	public Environment getEnvironment() {
		return environment;
	}

	// ----- INTERNAL METHODS -----

	/**
	 * Internal method which builds the replication manager. Can be overridden
	 * by subclasses.
	 * 
	 * @param node
	 *            The node to base the RM off of
	 * @param instance
	 *            The instance name to use
	 * @return The replication manager, ready for use
	 */
	protected ReplicationManager buildReplicationManager(Node node,
			String instance) {
		return new ReplicationManagerImpl(node, this, replicationFactor,
				instance);
	}

	/**
	 * Returns of the outstanding messages. This is a DEBUGGING method ONLY!
	 * 
	 * @return The list of all the outstanding messages
	 */
	@SuppressWarnings("rawtypes")
	public Continuation[] getOutstandingMessages() {
		return (Continuation[]) outstanding.values().toArray(
				new Continuation[0]);
	}

	/**
	 * Returns the endpoint associated with the Past - ONLY FOR TESTING - DO NOT
	 * USE
	 * 
	 * @return The endpoint
	 */
	public Endpoint getEndpoint() {
		return endpoint;
	}

	/**
	 * Returns a new uid for a message
	 * 
	 * @return A new id
	 */
	protected synchronized int getUID() {
		return id++;
	}

	/**
	 * Returns a continuation which will respond to the given message.
	 * 
	 * @return A new id
	 */
	@SuppressWarnings("rawtypes")
	protected Continuation getResponseContinuation(final PastMessage msg) {
		if (logger.level <= Logger.FINER)
			logger.log("Getting the Continuation to respond to the message "
					+ msg);
		final ContinuationMessage cmsg = (ContinuationMessage) msg;

		return new Continuation() {
			public void receiveResult(Object o) {
				cmsg.receiveResult(o); // Here calls cmsg.setResponse();
				endpoint.route(null, cmsg, msg.getSource());
			}

			public void receiveException(Exception e) {
				cmsg.receiveException(e);
				endpoint.route(null, cmsg, msg.getSource());
			}
		};
	}

	/**
	 * Do like above, but use a socket
	 * 
	 * @param msg
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	protected Continuation getFetchResponseContinuation(final PastMessage msg) {
		final ContinuationMessage cmsg = (ContinuationMessage) msg;

		return new Continuation() {
			public void receiveResult(Object o) {
				cmsg.receiveResult(o);
				endpoint.route(null, cmsg, msg.getSource());
			}

			public void receiveException(Exception e) {
				cmsg.receiveException(e);
				endpoint.route(null, cmsg, msg.getSource());
			}
		};
	}

	/**
	 * AppSocket -> ByteBuffer[]
	 * 
	 * Used for receiving the objects.
	 */
	WeakHashMap<AppSocket, ByteBuffer[]> pendingSocketTransactions = new WeakHashMap<AppSocket, ByteBuffer[]>();

	/**
	 * Sends a request message across the wire, and stores the appropriate
	 * continuation.
	 * 
	 * @param id
	 *            The destination id
	 * @param message
	 *            The message to send.
	 * @param command
	 *            The command to run once a result is received
	 */
	@SuppressWarnings("rawtypes")
	protected void sendRequest(Id id, PastMessage message, Continuation command) {
		sendRequest(id, message, null, command);
	}

	/**
	 * Sends a request message across the wire, and stores the appropriate
	 * continuation.
	 * 
	 * @param handle
	 *            The node handle to send directly too
	 * @param message
	 *            The message to send.
	 * @param command
	 *            The command to run once a result is received
	 */
	@SuppressWarnings("rawtypes")
	protected void sendRequest(NodeHandle handle, PastMessage message,
			Continuation command) {
		sendRequest(null, message, handle, command);
	}

	/**
	 * Sends a request message across the wire, and stores the appropriate
	 * continuation. Sends the message using the provided handle as a hint.
	 * 
	 * @param id
	 *            The destination id
	 * @param message
	 *            The message to send.
	 * @param handle
	 *            The first hop hint
	 * @param command
	 *            The command to run once a result is received
	 */
	@SuppressWarnings("rawtypes")
	protected void sendRequest(Id id, PastMessage message, NodeHandle hint,
			Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log("Sending request message " + message + " {"
					+ message.getUID() + "} to id " + id + " via " + hint);
		CancellableTask timer = endpoint.scheduleMessage(
				new MessageLostMessage(message.getUID(), getLocalNodeHandle(),
						id, message, hint), MESSAGE_TIMEOUT);
		insertPending(message.getUID(), timer, command);
		endpoint.route(id, message, hint);
	}

	/**
	 * Loads the provided continuation into the pending table
	 * 
	 * @param uid
	 *            The id of the message
	 * @param command
	 *            The continuation to run
	 */
	@SuppressWarnings("rawtypes")
	private void insertPending(int uid, CancellableTask timer,
			Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log("Loading continuation " + uid + " into pending table");
		timers.put(Integer.valueOf(uid), timer);
		outstanding.put(Integer.valueOf(uid), command);
	}

	/**
	 * Removes and returns the provided continuation from the pending table
	 * 
	 * @param uid
	 *            The id of the message
	 * @return The continuation to run
	 */
	@SuppressWarnings("rawtypes")
	private Continuation removePending(int uid) {
		if (logger.level <= Logger.FINER)
			logger.log("Removing and returning continuation " + uid
					+ " from pending table");
		CancellableTask timer = (CancellableTask) timers
				.remove(Integer.valueOf(uid));

		if (timer != null)
			timer.cancel();

		return (Continuation) outstanding.remove(Integer.valueOf(uid));
	}

	/**
	 * Handles the response message from a request.
	 * 
	 * @param message
	 *            The message that arrived
	 */
	@SuppressWarnings("rawtypes")
	void handleResponse(PastMessage message) {
		if (logger.level <= Logger.FINE)
			logger.log("handling reponse message " + message
					+ " from the request");
		Continuation command = removePending(message.getUID());

		if (command != null) {
			message.returnResponse(command, environment, instance);
		}
	}

	/**
	 * Internal method which returns the handles to an object. It first checks
	 * to see if the handles can be determined locally, and if so, returns.
	 * Otherwise, it sends a LookupHandles messsage out to find out the nodes.
	 * 
	 * @param id
	 *            The id to fetch the handles for
	 * @param max
	 *            The maximum number of handles to return
	 * @param command
	 *            The command to call with the result (NodeHandle[])
	 */
	@SuppressWarnings("rawtypes")
	protected void getHandles(Id id, final int max, Continuation command) {
		NodeHandleSet set = endpoint.replicaSet(id, max);

		if (set.size() == max) {
			command.receiveResult(set);
		} else {
			sendRequest(id, new LookupHandlesMessage(getUID(), id, max,
					getLocalNodeHandle(), id),
					new StandardContinuation(command) {
						public void receiveResult(Object o) {
							NodeHandleSet replicas = (NodeHandleSet) o;

							// check to make sure we've fetched the correct
							// number of replicas
							// the deal with this is for the small ring. If you
							// are requesting
							// 4 nodes, but the ring is only 3, you are only
							// going to get 3
							// Note: this is still kind of funky, because the
							// replicationFactor+1
							// argument is kind of weird, but I don't know how
							// to get it right
							// -Jeff 1/24/07
							if (Math.min(max, endpoint.replicaSet(
									endpoint.getLocalNodeHandle().getId(),
									replicationFactor + 1).size()) > replicas
									.size())
								parent
										.receiveException(new PastException(
												"Only received "
														+ replicas.size()
														+ " replicas - cannot insert as we know about more nodes."));
							else
								parent.receiveResult(replicas);
						}
					});
		}
	}

	/**
	 * Method which inserts the given object into the cache
	 * 
	 * @param content
	 *            The content to cache
	 */
	@SuppressWarnings("rawtypes")
	void cache(final PastContent content) {
		cache(content, new ListenerContinuation("Caching of " + content,
				environment));
	}

	/**
	 * Method which inserts the given object into the cache
	 * 
	 * @param content
	 *            The content to cache
	 * @param command
	 *            The command to run once done
	 */
	@SuppressWarnings("rawtypes")
	public void cache(final PastContent content, final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log("Inserting PastContent object " + content
					+ " into cache");

		if ((content != null) && (!content.isMutable()))
			storage.cache(content.getId(), null, content, command);
		else
			command.receiveResult(Boolean.valueOf(true));
	}

	/**
	 * Internal method which actually performs an insert for a given object.
	 * Here so that subclasses can override the types of insert messages which
	 * are sent across the wire.
	 * 
	 * @param obj
	 *            The object to insert
	 * @param builder
	 *            The object which builds the messages
	 * @param command
	 *            The command to call once done
	 * 
	 */
	@SuppressWarnings("rawtypes")
	protected void doInsert(final Id id, final MessageBuilder builder,
			Continuation command, final boolean useSocket) {
		// first, we get all of the replicas for this id
		getHandles(id, replicationFactor + 1,
				new StandardContinuation(command) {
					public void receiveResult(Object o) {
						NodeHandleSet replicas = (NodeHandleSet) o;
						if (logger.level <= Logger.FINER)
							logger.log("Received replicas " + replicas
									+ " for id " + id);

						// then we send inserts to each replica and wait for at
						// least
						// threshold * num to return successfully
						MultiContinuation multi = new MultiContinuation(parent,
								replicas.size()) {
							public boolean isDone() throws Exception {
								int numSuccess = 0;
								for (int i = 0; i < haveResult.length; i++)
									if ((haveResult[i])
											&& (Boolean.TRUE.equals(result[i])))
										numSuccess++;

								if (numSuccess >= (SUCCESSFUL_INSERT_THRESHOLD * haveResult.length))
									return true;

								if (super.isDone()) {
									for (int i = 0; i < result.length; i++)
										if (result[i] instanceof Exception)
											if (logger.level <= Logger.WARNING)
												logger.logException("result["
														+ i + "]:",
														(Exception) result[i]);

									throw new PastException("Had only "
											+ numSuccess
											+ " successful inserts out of "
											+ result.length + " - aborting.");
								}
								return false;
							}

							public Object getResult() {
								Boolean[] b = new Boolean[result.length];
								for (int i = 0; i < b.length; i++)
									b[i] = Boolean.valueOf((result[i] == null)
											|| Boolean.TRUE.equals(result[i]));

								return b;
							}
						};

						for (int i = 0; i < replicas.size(); i++) {
							NodeHandle handle = replicas.getHandle(i);
							PastMessage m = builder.buildMessage();
							Continuation c = new NamedContinuation(
									"InsertMessage to " + replicas.getHandle(i)
											+ " for " + id, multi
											.getSubContinuation(i));
							sendRequest(handle, m, c);
						}
					}
				});
	}

	// ----- PAST METHODS -----

	/**
	 * Inserts an object with the given ID into this instance of Past.
	 * Asynchronously returns a PastException to command, if the operation was
	 * unsuccessful. If the operation was successful, a Boolean[] is returned
	 * representing the responses from each of the replicas which inserted the
	 * object.
	 * 
	 * @param obj
	 *            the object to be inserted
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void insert(final PastContent obj, final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log("Inserting the object " + obj + " with the id "
					+ obj.getId());

		if (logger.level <= Logger.FINEST)
			logger.log(" Inserting data of class " + obj.getClass().getName()
					+ " under " + obj.getId().toStringFull());

		doInsert(obj.getId(), new MessageBuilder() {
			public PastMessage buildMessage() {
				return new InsertMessage(getUID(), obj, getLocalNodeHandle(),
						obj.getId());
			}
		}, new StandardContinuation(command) {
			public void receiveResult(final Object array) {
				cache(obj, new SimpleContinuation() {
					public void receiveResult(Object o) {
						parent.receiveResult(array);
					}
				});
			}
		}, socketStrategy.sendAlongSocket(SocketStrategy.TYPE_INSERT, obj));
	}

	/**
	 * Retrieves the object stored in this instance of Past with the given ID.
	 * Asynchronously returns a PastContent object as the result to the provided
	 * Continuation, or a PastException. This method is provided for
	 * convenience; its effect is identical to a lookupHandles() and a
	 * subsequent fetch() to the handle that is nearest in the network.
	 * 
	 * The client must authenticate the object. In case of failure, an alternate
	 * replica of the object can be obtained via lookupHandles() and fetch().
	 * 
	 * This method is not safe if the object is immutable and storage nodes are
	 * not trusted. In this case, clients should used the lookUpHandles method
	 * to obtains the handles of all primary replicas and determine which
	 * replica is fresh in an application-specific manner.
	 * 
	 * @param id
	 *            the key to be queried
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookup(final Id id, final Continuation command) {
		lookup(id, true, command);
	}

	/**
	 * Method which performs the same as lookup(), but allows the callee to
	 * specify if the data should be cached.
	 * 
	 * 
	 * @param id
	 *            the key to be queried
	 * @param cache
	 *            Whether or not the data should be cached
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookup(final Id id, final boolean cache,
			final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log(" Performing lookup on " + id.toStringFull());

		storage.getObject(id, new StandardContinuation(command) {
			public void receiveResult(Object o) {
				if (o != null) {
					command.receiveResult(o);
				} else {
					// send the request across the wire, and see if the result
					// is null or not
					sendRequest(id, new LookupMessage(getUID(), id,
							getLocalNodeHandle(), id), new NamedContinuation(
							"LookupMessage for " + id, this) {
						public void receiveResult(final Object o) {
							// if we have an object, we return it
							// otherwise, we must check all replicas in order to
							// make sure that
							// the object doesn't exist anywhere
							if (o != null) {
								// lastly, try and cache object locally for
								// future use
								if (cache) {
									cache((PastContent) o,
											new SimpleContinuation() {
												public void receiveResult(
														Object object) {
													command.receiveResult(o);
												}
											});
								} else {
									command.receiveResult(o);
								}
							} else {
								lookupHandles(id, replicationFactor + 1,
										new Continuation() {
											public void receiveResult(Object o) {
												PastContentHandle[] handles = (PastContentHandle[]) o;

												for (int i = 0; i < handles.length; i++) {
													if (handles[i] != null) {
														fetch(
																handles[i],
																new StandardContinuation(
																		parent) {
																	public void receiveResult(
																			final Object o) {
																		// lastly,
																		// try
																		// and
																		// cache
																		// object
																		// locally
																		// for
																		// future
																		// use
																		if (cache) {
																			cache(
																					(PastContent) o,
																					new SimpleContinuation() {
																						public void receiveResult(
																								Object object) {
																							command
																									.receiveResult(o);
																						}
																					});
																		} else {
																			command
																					.receiveResult(o);
																		}
																	}
																});

														return;
													}
												}

												// there were no replicas of the
												// object
												command.receiveResult(null);
											}

											public void receiveException(
													Exception e) {
												command.receiveException(e);
											}
										});
							}
						}

						public void receiveException(Exception e) {
							// If the lookup message failed , we then try to
							// fetch all of the handles, just
							// in case. This may fail too, but at least we
							// tried.
							receiveResult(null);
						}
					});
				}
			}
		});
	}

	/**
	 * Method which performs the same as lookup(), but also transfers a QueryPDU
	 * to the destination in order to handle the situation appropriately.
	 * 
	 * 
	 * @param id
	 *            the key to be queried
	 * @param cache
	 *            Whether or not the data should be cached
	 * @param queryPDU
	 *            the query terms and maybe some more data
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookup(final Id id, final boolean cache,
			final QueryPDU queryPDU, final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log(" Performing lookup on " + id.toStringFull());

		storage.getObject(id, new StandardContinuation(command) {
			public void receiveResult(Object o) {
				if (o != null) {
					// Do the similarity computation and scoring of terms and
					// return a mini ScoredCatalog (PastContent)
					if (o instanceof ceid.netcins.exo.catalog.Catalog) {
						int type = queryPDU.getType();
						Hashtable entries = ((ceid.netcins.exo.catalog.Catalog) o).getCatalogEntriesForQueryType(type);
						// Leave the job to be done asynchronously by the
						// Scorer thread
						scorer.addRequest(new SimilarityRequest(
								entries.values(), queryPDU.getData(), type,
								queryPDU.getK(), 
								queryPDU.getSourceUserProfile(), parent, 0));
						scorer.doNotify();
					} else {
						// debugging only
						System.out
								.println("Error: o is not Catalog (in deliver)");
						// send result back
						parent.receiveResult(new ResponsePDU(0));
					}
				} else {
					// send the request across the wire, and see if the result
					// is null or not
					sendRequest(id, new QueryMessage(getUID(), id,
							getLocalNodeHandle(), id, queryPDU),
							new NamedContinuation("QueryMessage for " + id,
									this) {
								public void receiveResult(final Object o) {
									// if we have an object, we return it
									// otherwise, we must check all replicas in
									// order to make sure that
									// the object doesn't exist anywhere
									if (o != null) {
										// lastly, try and cache object locally
										// for future use
										if (cache) {
											cache((PastContent) o,
													new SimpleContinuation() {
														public void receiveResult(
																Object object) {
															command
																	.receiveResult(o);
														}
													});
										} else {
											command.receiveResult(o);
										}
									} else {
										lookupHandles(id,
												replicationFactor + 1,
												new Continuation() {
													public void receiveResult(
															Object o) {
														PastContentHandle[] handles = (PastContentHandle[]) o;

														for (int i = 0; i < handles.length; i++) {
															if (handles[i] != null) {
																fetch(
																		handles[i],
																		new StandardContinuation(
																				parent) {
																			public void receiveResult(
																					final Object o) {
																				// lastly,
																				// try
																				// and
																				// cache
																				// object
																				// locally
																				// for
																				// future
																				// use
																				if (cache) {
																					cache(
																							(PastContent) o,
																							new SimpleContinuation() {
																								public void receiveResult(
																										Object object) {
																									command
																											.receiveResult(o);
																								}
																							});
																				} else {
																					command
																							.receiveResult(o);
																				}
																			}
																		});

																return;
															}
														}

														// there were no
														// replicas of the
														// object
														command
																.receiveResult(null);
													}

													public void receiveException(
															Exception e) {
														command
																.receiveException(e);
													}
												});
									}
								}

								public void receiveException(Exception e) {
									// If the lookup message failed , we then
									// try to fetch all of the handles, just
									// in case. This may fail too, but at least
									// we tried.
									receiveResult(null);
								}
							});
				}
			}
		});
	}

	/**
	 * Method which performs the same as lookup() (routing), but it creates a
	 * RetrieveConttMessage which contains the checksum to be retrieved.
	 * 
	 * @param id
	 *            the key to be queried
	 * @param cache
	 *            Whether or not the data should be cached
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookup(final Id id, final boolean cache,
			RetrieveContPDU retPDU, boolean getContent,
			final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log(" Performing lookup on " + id.toStringFull());

		// send the request across the wire, and see if the result is null or
		// not
		sendRequest(id, 
				getContent ?
						new RetrieveContMessage(getUID(), retPDU.getContentId(), getLocalNodeHandle(), id, retPDU) :
							new RetrieveContTagsMessage(getUID(), retPDU.getContentId(), getLocalNodeHandle(), id, retPDU),
							new NamedContinuation(
									(getContent ? "RetrieveContMessage" : "RetrieveContTagsMessage") +
									" for " + id, command) {
							public void receiveResult(final Object o) {
								// if we have an object, we return it
								// otherwise, we must check all replicas in order to make sure
								// that the object doesn't exist anywhere
								if (o != null) {

									command.receiveResult(o);

								} else {
									// TODO : examine if the tager's arrays need to be replicated to the leafset
									// If so then here we should put the lookupHandles code as above!!!
									command.receiveResult(null); // o is NULL
								}
							}

							public void receiveException(Exception e) {
								// If the lookup message failed , we then try to fetch all of
								// the handles, just
								// in case. This may fail too, but at least we tried.
								receiveResult(null);
							}
						}
		);

	}

	/**
	 * Method which performs the same as lookup(), but also transfers a
	 * SocialQueryPDU to the destination in order to handle the situation
	 * appropriately.
	 * 
	 * 
	 * @param id
	 *            the key to be queried
	 * @param cache
	 *            Whether or not the data should be cached
	 * @param queryPDU
	 *            the query terms and maybe some more data
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookup(final Id id, final boolean cache,
			final SocialQueryPDU queryPDU, final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log(" Performing lookup on " + id.toStringFull());

		storage.getObject(id, new StandardContinuation(command) {
			public void receiveResult(Object o) {
				if (o != null) {
					// TODO : Maybe we want to SCORE this local Catalog
					command.receiveResult(o); // If we find here then we execute command
				} else {
					// send the request across the wire, and see if the result
					// is null or not
					sendRequest(id, new SocialQueryMessage(getUID(), id,
							getLocalNodeHandle(), id, queryPDU),
							new NamedContinuation("SocialQueryMessage for "
									+ id, this) {
								public void receiveResult(final Object o) {
									// if we have an object, we return it
									// otherwise, we must check all replicas in
									// order to make sure that
									// the object doesn't exist anywhere
									if (o != null) {
										// lastly, try and cache object locally
										// for future use
										if (cache) {
											cache((PastContent) o,
													new SimpleContinuation() {
														public void receiveResult(
																Object object) {
															command
																	.receiveResult(o);
														}
													});
										} else {
											command.receiveResult(o);
										}
									} else {
										lookupHandles(id,
												replicationFactor + 1,
												new Continuation() {
													public void receiveResult(
															Object o) {
														PastContentHandle[] handles = (PastContentHandle[]) o;

														for (int i = 0; i < handles.length; i++) {
															if (handles[i] != null) {
																fetch(
																		handles[i],
																		new StandardContinuation(
																				parent) {
																			public void receiveResult(
																					final Object o) {
																				// lastly,
																				// try
																				// and
																				// cache
																				// object
																				// locally
																				// for
																				// future
																				// use
																				if (cache) {
																					cache(
																							(PastContent) o,
																							new SimpleContinuation() {
																								public void receiveResult(
																										Object object) {
																									command
																											.receiveResult(o);
																								}
																							});
																				} else {
																					command
																							.receiveResult(o);
																				}
																			}
																		});

																return;
															}
														}

														// there were no
														// replicas of the
														// object
														command
																.receiveResult(null);
													}

													public void receiveException(
															Exception e) {
														command
																.receiveException(e);
													}
												});
									}
								}

								public void receiveException(Exception e) {
									// If the lookup message failed , we then
									// try to fetch all of the handles, just
									// in case. This may fail too, but at least
									// we tried.
									receiveResult(null);
								}
							});
				}
			}
		});
	}

	/**
	 * Wrapper for lookup process, which specifically handles a search in the
	 * unstructured friends network. A FriendQueryMessage is routed using the
	 * friend's NodeHandle as a first hop hint!
	 * 
	 * @param id
	 *            the key to be queried
	 * @param destNodeHandle
	 * 			  the destination node handle used as a hint for routing 
	 * @param cache
	 *            Whether or not the data should be cached
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookup(final Id id, final NodeHandle destNodeHandle,
			final boolean cache, QueryPDU qPDU,	final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log(" Performing lookup on " + id.toStringFull());

		// send the request across the wire, and see if the result is null or
		// not
		sendRequest(id, new FriendQueryMessage(getUID(),
				getLocalNodeHandle(), id, qPDU), destNodeHandle,
				new NamedContinuation(
				"FriendQueryMessage for " + id, command) {
			public void receiveResult(final Object o) {
				// if we have an object, we return it
				// otherwise, we must check all replicas in order to make sure
				// that
				// the object doesn't exist anywhere
				if (o != null) {

					command.receiveResult(o);

				} else {
					// TODO : examine if the tager's arrays need to be replicated to the leafset
					// If so then here we should put the lookupHandles code as above!!!
					command.receiveResult(null); // o is NULL
				}
			}

			public void receiveException(Exception e) {
				// If the lookup message failed , we then try to fetch all of
				// the handles, just
				// in case. This may fail too, but at least we tried.
				receiveResult(null);
			}
		});
	}


	/**
	 * Wrapper for lookup process. 
	 * Hint: A message can be routed using a NodeHandle as a first hop target!
	 * 
	 * @param id The key to be queried.
	 * @param type The type of message to route. 
	 * @param extra_args A dictionary of extra arguments to be passed. 
	 * @param command Command to be performed when the result is received.
	 */
	@SuppressWarnings("rawtypes")
	public void lookup(final Id id, final short type,
			final HashMap<String, Object> extra_args,
			final Continuation command) {
		if (logger.level <= Logger.FINER)
			logger.log(" Performing lookup on " + id.toStringFull());

		NodeHandle destNodeHandle = extra_args.containsKey("nodeHandle")?
				((NodeHandle) extra_args.get("nodeHandle")):null;		
		ContinuationMessage message = null;
		switch (type) {
			case MessageType.GetUserProfile:
				message = new GetUserProfileMessage(getUID(), id,
						getLocalNodeHandle(), id);
				break;
			case MessageType.FriendRequest:
				message = new FriendReqMessage(getUID(), getLocalNodeHandle(), 
						id, (FriendReqPDU)extra_args.get("PDU"));
				break;
			case MessageType.FriendReject:
				message = new FriendRejectMessage(getUID(), id, getLocalNodeHandle(),
						id, (FriendReqPDU)extra_args.get("PDU"));
				break;
			case MessageType.FriendAccept:
				message = new FriendAcceptMessage(getUID(), id, getLocalNodeHandle(),
						id, (FriendReqPDU)extra_args.get("PDU"));
				break;
			case MessageType.TagContent:
				message = new TagContentMessage(getUID(), id, getLocalNodeHandle(),
						id,	(TagPDU)extra_args.get("PDU"));
				break;
			case MessageType.TagUser:
				message = new TagUserMessage(getUID(), id, getLocalNodeHandle(),
						id,	(TagPDU)extra_args.get("PDU"));
				break;
			case MessageType.RetrieveContentTags:
				message = new RetrieveContTagsMessage(getUID(), (Id)extra_args.get("ContentId"), getLocalNodeHandle(),
						id,	 (RetrieveContPDU)extra_args.get("PDU"));
				break;
			case MessageType.RetrieveContentIDs:
				message = new RetrieveContIDsMessage(getUID(), getLocalNodeHandle(), id);
				break;
			default:
				logger.log("Unknown message type. Bailing out...");
				return;
		}
		
		// send the request across the wire, and see if the result is null or not
		sendRequest(id, message, destNodeHandle,
				new NamedContinuation(message.getClass()
						.getSimpleName() + " for " + id, command) {
			public void receiveResult(final Object o) {
				// if we have an object, we return it
				// otherwise, we may want to check all replicas in order to make
				// sure the object doesn't exist anywhere
				command.receiveResult(o);
			}
			public void receiveException(Exception e) {
				receiveResult(null);
			}
		});

	}
	
	/**
	 * Retrieves the handles of up to max replicas of the object stored in this
	 * instance of Past with the given ID. Asynchronously returns an array of
	 * PastContentHandles as the result to the provided Continuation, or a
	 * PastException.
	 * 
	 * Each replica handle is obtained from a different primary storage root for
	 * the the given key. If max exceeds the replication factor r of this Past
	 * instance, only r replicas are returned.
	 * 
	 * This method will return a PastContentHandle[] array containing all of the
	 * handles.
	 * 
	 * @param id
	 *            the key to be queried
	 * @param max
	 *            the maximal number of replicas requested
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookupHandles(final Id id, int max, final Continuation command) {
		if (logger.level <= Logger.FINE)
			logger.log("Retrieving handles of up to " + max
					+ " replicas of the object stored in Past with id " + id);

		if (logger.level <= Logger.FINER)
			logger.log("Fetching up to " + max + " handles of "
					+ id.toStringFull());

		getHandles(id, max, new StandardContinuation(command) {
			public void receiveResult(Object o) {
				NodeHandleSet replicas = (NodeHandleSet) o;
				if (logger.level <= Logger.FINER)
					logger.log("Receiving replicas " + replicas
							+ " for lookup Id " + id);

				MultiContinuation multi = new MultiContinuation(parent,
						replicas.size()) {
					public Object getResult() {
						PastContentHandle[] p = new PastContentHandle[result.length];

						for (int i = 0; i < result.length; i++)
							if (result[i] instanceof PastContentHandle)
								p[i] = (PastContentHandle) result[i];

						return p;
					}
				};

				for (int i = 0; i < replicas.size(); i++)
					lookupHandle(id, replicas.getHandle(i), multi
							.getSubContinuation(i));
			}
		});
	}

	/**
	 * Retrieves the handle for the given object stored on the requested node.
	 * Asynchronously returns a PostContentHandle (or null) to the provided
	 * continuation.
	 * 
	 * @param id
	 *            the key to be queried
	 * @param handle
	 *            The node on which the handle is requested
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void lookupHandle(Id id, NodeHandle handle, Continuation command) {
		if (logger.level <= Logger.FINE)
			logger.log("Retrieving handle for id " + id + " from node "
					+ handle);

		sendRequest(handle, new FetchHandleMessage(getUID(), id,
				getLocalNodeHandle(), handle.getId()), new NamedContinuation(
				"FetchHandleMessage to " + handle + " for " + id, command));
	}

	/**
	 * Retrieves the object associated with a given content handle.
	 * Asynchronously returns a PastContent object as the result to the provided
	 * Continuation, or a PastException.
	 * 
	 * The client must authenticate the object. In case of failure, an alternate
	 * replica can be obtained using a different handle obtained via
	 * lookupHandles().
	 * 
	 * @param id
	 *            the key to be queried
	 * @param command
	 *            Command to be performed when the result is received
	 */
	@SuppressWarnings("rawtypes")
	public void fetch(PastContentHandle handle, Continuation command) {
		if (logger.level <= Logger.FINE)
			logger.log("Retrieving object associated with content handle "
					+ handle);

		if (logger.level <= Logger.FINER)
			logger.log("Fetching object under id "
					+ handle.getId().toStringFull() + " on "
					+ handle.getNodeHandle());

		NodeHandle han = handle.getNodeHandle();
		sendRequest(han, new FetchMessage(getUID(), handle,
				getLocalNodeHandle(), han.getId()), new NamedContinuation(
				"FetchMessage to " + handle.getNodeHandle() + " for "
						+ handle.getId(), command));
	}

	/**
	 * get the nodeHandle of the local Past node
	 * 
	 * @return the nodehandle
	 */
	public NodeHandle getLocalNodeHandle() {
		return endpoint.getLocalNodeHandle();
	}

	/**
	 * Returns the number of replicas used in this Past
	 * 
	 * @return the number of replicas for each object
	 */
	public int getReplicationFactor() {
		return replicationFactor;
	}

	// ----- COMMON API METHODS -----

	/**
	 * This method is invoked on applications when the underlying node is about
	 * to forward the given message with the provided target to the specified
	 * next hop. Applications can change the contents of the message, specify a
	 * different nextHop (through re-routing), or completely terminate the
	 * message.
	 * 
	 * @param message
	 *            The message being sent, containing an internal message along
	 *            with a destination key and nodeHandle next hop.
	 * 
	 * @return Whether or not to forward the message further
	 */
	public boolean forward(final RouteMessage message) {
		Message internal;
		try {
			internal = message.getMessage(endpoint.getDeserializer());
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		if (internal instanceof LookupMessage) {
			final LookupMessage lmsg = (LookupMessage) internal;
			Id id = lmsg.getId();

			// if it is a request, look in the cache
			if (!lmsg.isResponse()) {
				if (logger.level <= Logger.FINER)
					logger.log("Lookup message " + lmsg
							+ " is a request; look in the cache");
				if (storage.exists(id)) {
					// deliver the message, which will do what we want
					if (logger.level <= Logger.FINE)
						logger.log("Request for " + id
								+ " satisfied locally - responding");
					deliver(endpoint.getId(), lmsg);
					return false;
				}
			}
		} else if (internal instanceof LookupHandlesMessage) {
			LookupHandlesMessage lmsg = (LookupHandlesMessage) internal;

			if (!lmsg.isResponse()) {
				if (endpoint.replicaSet(lmsg.getId(), lmsg.getMax()).size() == lmsg
						.getMax()) {
					if (logger.level <= Logger.FINE)
						logger.log("Hijacking lookup handles request for "
								+ lmsg.getId());

					deliver(endpoint.getId(), lmsg);
					return false;
				}
			}
		} else if (internal instanceof QueryMessage) {
			final QueryMessage qmsg = (QueryMessage) internal;

			// Increase the hits counter for per node load counting
			this.hits++;

			// Whether it is request or response increase by 1 the hops counter
			if (!qmsg.getSource().getId().equals(
					this.getLocalNodeHandle().getId())
					&& !qmsg.isResponse()) {
				qmsg.addHop();
			}
		}

		return true;
	}

	/**
	 * This method is called on the application at the destination node for the
	 * given id.
	 * 
	 * @param id
	 *            The destination id of the message
	 * @param message
	 *            The message being sent
	 */
	@SuppressWarnings("rawtypes")
	public void deliver(Id id, Message message) {
		final PastMessage msg = (PastMessage) message;

		if (msg.isResponse()) {
			handleResponse((PastMessage) message);
		} else {
			if (logger.level <= Logger.INFO)
				logger.log("Received message " + message + " with destination "
						+ id);

			if (msg instanceof InsertMessage) {
				final InsertMessage imsg = (InsertMessage) msg;

				// make sure the policy allows the insert
				if (policy.allowInsert(imsg.getContent())) {
					inserts++;
					final Id msgid = imsg.getContent().getId();

					lockManager.lock(msgid, new StandardContinuation(
							getResponseContinuation(msg)) {

						public void receiveResult(Object result) {
							storage.getObject(msgid, new StandardContinuation(
									parent) {
								public void receiveResult(Object o) {
									try {
										// allow the object to check the insert,
										// and then insert the data
										PastContent content = imsg.getContent()
												.checkInsert(msgid,
														(PastContent) o);
										storage
												.store(
														msgid,
														null,
														content,
														new StandardContinuation(
																parent) {
															public void receiveResult(
																	Object result) {
																getResponseContinuation(
																		msg)
																		.receiveResult(
																				result);
																lockManager
																		.unlock(msgid);
															}
														});
									} catch (PastException e) {
										parent.receiveException(e);
									}
								}
							});
						}
					});
				} else {
					getResponseContinuation(msg).receiveResult(
							Boolean.valueOf(false));
				}
			} else if (msg instanceof LookupMessage) {
				final LookupMessage lmsg = (LookupMessage) msg;
				lookups++;

				// if the data is here, we send the reply, as well as push a
				// cached copy
				// back to the previous node
				storage.getObject(lmsg.getId(), new StandardContinuation(
						getResponseContinuation(lmsg)) {
					public void receiveResult(Object o) {
						if (logger.level <= Logger.FINE)
							logger.log("Received object " + o + " for id "
									+ lmsg.getId());

						// send result back
						parent.receiveResult(o);

						// if possible, pushed copy into previous hop cache
						if ((lmsg.getPreviousNodeHandle() != null)
								&& (o != null)
								&& (!((PastContent) o).isMutable())) {
							NodeHandle handle = lmsg.getPreviousNodeHandle();
							if (logger.level <= Logger.FINE)
								logger.log("Pushing cached copy of "
										+ ((PastContent) o).getId() + " to "
										+ handle);

							// CacheMessage cmsg = new CacheMessage(getUID(),
							// (PastContent) o, getLocalNodeHandle(),
							// handle.getId());
							// endpoint.route(null, cmsg, handle);
						}
					}
				});
			} else if (msg instanceof LookupHandlesMessage) {
				LookupHandlesMessage lmsg = (LookupHandlesMessage) msg;
				NodeHandleSet set = endpoint.replicaSet(lmsg.getId(), lmsg
						.getMax());
				if (logger.level <= Logger.FINER)
					logger.log("Returning replica set " + set
							+ " for lookup handles of id " + lmsg.getId()
							+ " max " + lmsg.getMax() + " at "
							+ endpoint.getId());
				getResponseContinuation(msg).receiveResult(set);
			} else if (msg instanceof FetchMessage) {
				FetchMessage fmsg = (FetchMessage) msg;
				lookups++;

				Continuation c;
				// c = getResponseContinuation(msg);
				c = getFetchResponseContinuation(msg); // has to be special to
														// determine how to send
														// the message

				storage.getObject(fmsg.getHandle().getId(), c);
			} else if (msg instanceof FetchHandleMessage) {
				final FetchHandleMessage fmsg = (FetchHandleMessage) msg;
				fetchHandles++;

				storage.getObject(fmsg.getId(), new StandardContinuation(
						getResponseContinuation(msg)) {
					public void receiveResult(Object o) {
						PastContent content = (PastContent) o;

						if (content != null) {
							if (logger.level <= Logger.FINE)
								logger
										.log("Retrieved data for fetch handles of id "
												+ fmsg.getId());
							parent.receiveResult(content
									.getHandle(DHTService.this));
						} else {
							parent.receiveResult(null);
						}
					}
				});
			} else if (msg instanceof CacheMessage) {
				cache(((CacheMessage) msg).getContent());
			} else {
				if (logger.level <= Logger.SEVERE)
					logger.log("ERROR - Received message " + msg
							+ "of unknown type.");
			}
		}
	}

	/**
	 * This method is invoked to inform the application that the given node has
	 * either joined or left the neighbor set of the local node, as the set
	 * would be returned by the neighborSet call.
	 * 
	 * @param handle
	 *            The handle that has joined/left
	 * @param joined
	 *            Whether the node has joined or left
	 */
	public void update(NodeHandle handle, boolean joined) {
	}

	// ----- REPLICATION MANAGER METHODS -----

	/**
	 * This upcall is invoked to tell the client to fetch the given id, and to
	 * call the given command with the boolean result once the fetch is
	 * completed. The client *MUST* call the command at some point in the
	 * future, as the manager waits for the command to return before continuing.
	 * 
	 * @param id
	 *            The id to fetch
	 */
	@SuppressWarnings("rawtypes")
	public void fetch(final Id id, NodeHandle hint, Continuation command) {
		if (logger.level <= Logger.FINER)
			logger
					.log("Sending out replication fetch request for the id "
							+ id);

		policy.fetch(id, hint, null, this, new StandardContinuation(command) {
			public void receiveResult(Object o) {
				if (o == null) {
					if (logger.level <= Logger.WARNING)
						logger.log("Could not fetch id " + id
								+ " - policy returned null in namespace "
								+ instance);
					parent.receiveResult(Boolean.valueOf(false));
				} else {
					if (logger.level <= Logger.FINEST)
						logger.log("inserting replica of id " + id);

					if (!(o instanceof PastContent))
						if (logger.level <= Logger.WARNING)
							logger.log("ERROR! Not PastContent "
									+ o.getClass().getName() + " " + o);
					storage.getStorage().store(((PastContent) o).getId(), null,
							(PastContent) o, parent);
				}
			}
		});
	}

	/**
	 * This upcall is to notify the client that the given id can be safely
	 * removed from the storage. The client may choose to perform advanced
	 * behavior, such as caching the object, or may simply delete it.
	 * 
	 * @param id
	 *            The id to remove
	 */
	@SuppressWarnings("rawtypes")
	public void remove(final Id id, Continuation command) {
		storage.unstore(id, command);
	}

	/**
	 * This upcall should return the set of keys that the application currently
	 * stores in this range. Should return a empty IdSet (not null), in the case
	 * that no keys belong to this range.
	 * 
	 * @param range
	 *            the requested range
	 */
	public IdSet scan(IdRange range) {
		return storage.getStorage().scan(range);
	}

	/**
	 * This upcall should return the set of keys that the application currently
	 * stores. Should return a empty IdSet (not null), in the case that no keys
	 * belong to this range.
	 * 
	 * @param range
	 *            the requested range
	 */
	public IdSet scan() {
		return storage.getStorage().scan();
	}

	/**
	 * This upcall should return whether or not the given id is currently stored
	 * by the client.
	 * 
	 * @param id
	 *            The id in question
	 * @return Whether or not the id exists
	 */
	public boolean exists(Id id) {
		return storage.getStorage().exists(id);
	}

	@SuppressWarnings("rawtypes")
	public void existsInOverlay(Id id, Continuation command) {
		lookupHandles(id, replicationFactor + 1, new StandardContinuation(
				command) {
			public void receiveResult(Object result) {
				Object results[] = (Object[]) result;
				for (int i = 0; i < results.length; i++) {
					if (results[i] instanceof PastContentHandle) {
						parent.receiveResult(Boolean.TRUE);
						return;
					}
				}
				parent.receiveResult(Boolean.FALSE);
			}
		});
	}

	@SuppressWarnings("rawtypes")
	public void reInsert(Id id, Continuation command) {
		storage.getObject(id, new StandardContinuation(command) {
			public void receiveResult(final Object o) {
				insert((PastContent) o, new StandardContinuation(parent) {
					public void receiveResult(Object result) {
						Boolean results[] = (Boolean[]) result;
						for (int i = 0; i < results.length; i++) {
							if (results[i].booleanValue()) {
								parent.receiveResult(Boolean.TRUE);
								return;
							}
						}
						parent.receiveResult(Boolean.FALSE);
					}
				});
			}
		});
	}

	// ----- UTILITY METHODS -----

	/**
	 * Returns the replica manager for this Past instance. Should *ONLY* be used
	 * for testing. Messing with this will cause unknown behavior.
	 * 
	 * @return This Past's replica manager
	 */
	public Replication getReplication() {
		return replicaManager.getReplication();
	}

	/**
	 * Returns this Past's storage manager. Should *ONLY* be used for testing.
	 * Messing with this will cause unknown behavior.
	 * 
	 * @return This Past's storage manager.
	 */
	public StorageManager getStorageManager() {
		return storage;
	}

	/**
	 * Class which builds a message
	 */
	public interface MessageBuilder {
		public PastMessage buildMessage();
	}

	public String getInstance() {
		return instance;
	}

	public void setContentDeserializer(PastContentDeserializer deserializer) {
		contentDeserializer = deserializer;
	}

	public void setContentHandleDeserializer(
			PastContentHandleDeserializer deserializer) {
		contentHandleDeserializer = deserializer;
	}

}