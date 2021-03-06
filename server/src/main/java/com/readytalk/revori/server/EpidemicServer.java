/* Copyright (c) 2010-2012, Revori Contributors

   Permission to use, copy, modify, and/or distribute this software
   for any purpose with or without fee is hereby granted, provided
   that the above copyright notice and this permission notice appear
   in all copies. */

package com.readytalk.revori.server;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.concurrent.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import com.google.common.collect.ObjectArrays;
import com.readytalk.revori.Column;
import com.readytalk.revori.ConflictResolver;
import com.readytalk.revori.DiffResult;
import com.readytalk.revori.DuplicateKeyResolution;
import com.readytalk.revori.ForeignKeyResolver;
import com.readytalk.revori.Revision;
import com.readytalk.revori.RevisionBuilder;
import com.readytalk.revori.Revisions;
import com.readytalk.revori.Table;
import com.readytalk.revori.imp.Constants;
import com.readytalk.revori.server.protocol.Protocol;
import com.readytalk.revori.server.protocol.ReadContext;
import com.readytalk.revori.server.protocol.Readable;
import com.readytalk.revori.server.protocol.Stringable;
import com.readytalk.revori.server.protocol.Writable;
import com.readytalk.revori.server.protocol.WriteContext;
import com.readytalk.revori.subscribe.Subscription;
import com.readytalk.revori.util.BufferOutputStream;
import com.readytalk.revori.util.Util;

@ThreadSafe
public class EpidemicServer implements NetworkServer {
	private static final Logger log = LoggerFactory
			.getLogger(EpidemicServer.class);
	private static final Marker DEBUG_HELLO = MarkerFactory
			.getMarker("debug_hello");
	private static final Marker DEBUG_VIEW = MarkerFactory
			.getMarker("debug_view");
	private static final Marker DEBUG_SEND = MarkerFactory
			.getMarker("debug_send");
	private static final Marker DEBUG_RECEIVE = MarkerFactory
			.getMarker("debug_receive");
	private static final Marker DEBUG_STATE = MarkerFactory
			.getMarker("debug_state");
	private static final Marker DEBUG_UPDATE = MarkerFactory
			.getMarker("debug_update");

	private static final UUID DefaultInstance = UUID
			.fromString("1c8f9a38-aad4-0d8c-8d62-b52500a8dfa1");

	private static final int End = 0;
	private static final int Descend = 1;
	private static final int Ascend = 2;
	private static final int Key = 3;
	private static final int Delete = 4;
	private static final int Insert = 5;

	private String id;
	private final Set<Runnable> listeners = new HashSet<Runnable>();
	private final NodeConflictResolver conflictResolver;
	private final ForeignKeyResolver foreignKeyResolver;
	private final Network network;
	private final Object lock = new Object();
	private final Map<NodeKey, NodeState> states = new HashMap<NodeKey, NodeState>();
	private final Map<NodeID, NodeState> directlyConnectedStates = new HashMap<NodeID, NodeState>();
	private final NodeState localNode;
	private long nextLocalSequenceNumber = 1;

	public EpidemicServer(NodeConflictResolver conflictResolver,
			ForeignKeyResolver foreignKeyResolver, Network network,
			NodeID self, UUID instance) {
		this.conflictResolver = conflictResolver;
		this.foreignKeyResolver = foreignKeyResolver;
		this.network = network;
		this.localNode = state(new NodeKey(self, instance));
		this.id = self.asString();
	}

	public EpidemicServer(NodeConflictResolver conflictResolver,
			ForeignKeyResolver foreignKeyResolver, Network network, NodeID self) {
		this(conflictResolver, foreignKeyResolver, network, self, UUID
				.randomUUID());
	}

	public void dump(java.io.PrintStream out) {
		out.println(localNode.key.toString());
		for (NodeState state : new java.util.TreeMap<NodeKey, NodeState>(states)
				.values()) {
			out.print("  ");
			out.print(state.key.toString());
			out.println(" acknowledged");
			for (Map.Entry<NodeKey, Record> e : new java.util.TreeMap<NodeKey, Record>(
					state.acknowledged).entrySet()) {
				out.print("    ");
				out.print(e.getKey());
				out.print(": ");
				out.println(e.getValue().sequenceNumber);
			}
			if (state.connectionState != null) {
				out.print("  ");
				out.print(state.key.toString());
				out.println(" last sent");
				for (Map.Entry<NodeKey, Record> e : new java.util.TreeMap<NodeKey, Record>(
						state.connectionState.lastSent).entrySet()) {
					out.print("    ");
					out.print(e.getKey());
					out.print(": ");
					out.println(e.getValue().sequenceNumber);
				}
			}
		}
	}

	private void debugMessage(Marker marker, String message, Object... objects) {
		if (log.isDebugEnabled(marker)) {
			log.debug(marker, "{}: {}: " + message, ObjectArrays.concat(id,
					ObjectArrays.concat(
							(localNode != null ? localNode.key.toString()
									: "(null)"), objects)));
		}
	}

	@Override
	public synchronized Subscription registerListener(final Runnable listener) {
		listeners.add(listener);
		listener.run();
		return new Subscription() {
			@Override
			public void cancel() {
				listeners.remove(listener);
			}
		};
	}

	// listeners are removed after being run
	public synchronized void registerSyncListener(NodeID node, Runnable listener) {
		NodeState state = state(new NodeKey(node, DefaultInstance));
		ConnectionState cs = state.connectionState;
		if (cs != null && cs.gotSync) {
			listener.run();
		} else {
			state.syncListeners.add(listener);
		}
	}

	public void setId(String id) {
		this.id = id;
	}

	@Override
	public void updateView(Set<NodeID> directlyConnectedNodes) {
		debugMessage(DEBUG_VIEW, "update view to {}", directlyConnectedNodes);

		synchronized (lock) {
			for (Iterator<NodeState> it = directlyConnectedStates.values()
					.iterator(); it.hasNext();) {
				NodeState state = it.next();
				if (!directlyConnectedNodes.contains(state.key.id)) {
					debugMessage(DEBUG_VIEW,
							"remove directly connected state {}", state.key.id);
					it.remove();
					state.connectionState = null;
				}
			}

			for (NodeID node : directlyConnectedNodes) {
				NodeState state = directlyConnectedStates.get(node);
				if (state == null) {
					state = new NodeState(new NodeKey(node, DefaultInstance));
					state.head = tail(state);
					directlyConnectedStates.put(node, state);
				}

				if (state.connectionState == null) {
					debugMessage(DEBUG_VIEW, "add directly connected state {}",
							state.key.id);
					state.connectionState = new ConnectionState();
					state.connectionState.readyToReceive = true;

					sendNext(state);
				}
			}
		}
	}

	@Override
	public Revision head() {
		return localNode.head.revision;
	}

	@Override
	public void merge(Revision base, Revision fork) {
		synchronized (lock) {
			Revision head = base.merge(localNode.head.revision, fork,
					conflictResolver(), foreignKeyResolver);

			if (head != localNode.head.revision) {
				acceptRevision(localNode, nextLocalSequenceNumber++, head);
			}
		}
	}

	public ConflictResolver conflictResolver() {
		return new MyConflictResolver(localNode.key.id, localNode.key.id,
				conflictResolver);
	}

	public ForeignKeyResolver foreignKeyResolver() {
		return foreignKeyResolver;
	}

	@Override
	public void accept(NodeID source, Readable message) {
		synchronized (lock) {
			((Message) message).deliver(source, this);
		}
	}

	private static void expect(boolean v) {
		if (!v) {
			throw new RuntimeException();
		}
	}

	private void send(NodeState state, Writable message) {
		expect(state.connectionState != null);
		expect(state.connectionState.readyToReceive);

		network.send(localNode.key.id, state.key.id, message);
	}

	private NodeState state(NodeKey key) {
		NodeState state = states.get(key);
		if (state == null) {
			states.put(key, state = new NodeState(key));

			initState(state);
		}

		return state;
	}

	private Record head(NodeState state) {
		Record head = state.head;
		while (head.next != null) {
			head = head.next;
		}
		return head;
	}

	private Record tail(NodeState state) {
		// todo: switch from weak references to reference counting to
		// ensure that we don't follow the chain of records back
		// further than we need to.
		Record tail = state.head;
		if (tail == null) {
			return new Record(state.key, Revisions.Empty, 0, null);
		}

		Record previous;
		while (tail.previous != null
				&& (previous = tail.previous.get()) != null) {
			tail = previous;
		}

		if (tail.merged != null && tail.merged.node == state.key) {
			tail = tail.merged;
		}

		Record r;
		if (tail.sequenceNumber == 0) {
			r = tail;
		} else {
			r = new Record(state.key, Revisions.Empty, 0, null);
			r.next = tail;
		}

		return r;
	}

	private void initState(NodeState state) {
		state.head = tail(state);

		for (NodeState s : states.values()) {
			debugMessage(DEBUG_STATE, "{} sees {} at 0 {}", s.key, state.key,
					state.head.hashCode());

			if (!state.key.instance.equals(DefaultInstance)) {
				s.acknowledged.put(state.key, state.head);
				if (s.connectionState != null) {
					s.connectionState.lastSent.remove(state.key);
				}
			}

			state.acknowledged.put(s.key, tail(s));
		}
	}

	private boolean readyForDataFromNewNode() {
		for (NodeState s : directlyConnectedStates.values()) {
			if (s.connectionState.sentHello && !s.connectionState.gotSync) {
				return false;
			}
		}
		return true;
	}

	private void sendNext() {
		for (NodeState state : directlyConnectedStates.values()) {
			sendNext(state);
		}
	}

	private void sendNext(NodeState state) {
		ConnectionState cs = state.connectionState;

		if (!cs.readyToReceive) {
			debugMessage(DEBUG_SEND, "not ready to receive: {}", state.key);
			return;
		}

		if (!cs.sentHello && readyForDataFromNewNode()) {
			state.connectionState.sentHello = true;

			debugMessage(DEBUG_HELLO, "hello to {}", state.key);
			send(state, new Hello(localNode.key.instance));
			return;
		}

		if (!cs.gotHello) {
			debugMessage(DEBUG_HELLO, "no hello: {}", state.key);
			return;
		}

		for (NodeState other : states.values()) {
			boolean update = needsUpdate(state, other.head);
			debugMessage(DEBUG_UPDATE,
					"sendNext: other: {}, state: {}, nu: {}", other.key,
					state.key, update);
			if (other != state && update) {
				cs.sentSync = false;
				sendUpdate(state, other.head);
				return;
			}
		}

		if (!cs.sentSync) {
			cs.sentSync = true;
			debugMessage(DEBUG_SEND, "sync to {}", state.key);
			send(state, new Sync(localNode.key.instance));
			return;
		}
	}

	private boolean needsUpdate(NodeState state, Record target) {
		Record acknowledged = state.acknowledged.get(target.node);

		debugMessage(DEBUG_UPDATE, "needsUpdate(asn: {} {}, tsn: {} {})",
				acknowledged.sequenceNumber, acknowledged.hashCode(),
				target.sequenceNumber, target.hashCode());

		if (acknowledged.sequenceNumber < target.sequenceNumber) {
			Record lastSent = state.connectionState.lastSent.get(target.node);
			if (lastSent == null) {
				state.connectionState.lastSent.put(target.node, acknowledged);
				return true;
			} else if (lastSent.sequenceNumber < acknowledged.sequenceNumber) {
				lastSent = acknowledged;
				state.connectionState.lastSent.put(target.node, lastSent);
			}

			debugMessage(DEBUG_UPDATE, "needsUpdate(lsn: {}, {})",
					lastSent.sequenceNumber, lastSent.hashCode());

			return lastSent.sequenceNumber < target.sequenceNumber;
		}

		return false;
	}

	private void sendUpdate(NodeState state, Record target) {
		while (true) {
			Record lastSent = state.connectionState.lastSent.get(target.node);
			Record record = lastSent.next;
			debugMessage(
					DEBUG_UPDATE,
					"ls: {}, rec: {}",
					lastSent.hashCode(),
					(record == null ? "(null)" : String.valueOf(record
							.hashCode())));

			if (record.merged != null) {
				if (needsUpdate(state, record.merged)) {
					target = record.merged;
					continue;
				}

				debugMessage(DEBUG_SEND, "send ack to {}: {} {} merged {} {}",
						state.key, record.node, record.sequenceNumber,
						record.merged.node, record.merged.sequenceNumber);

				send(state, new Ack(record.node, record.sequenceNumber,
						record.merged.node, record.merged.sequenceNumber));
			} else if (!target.node.equals(state.key)) {
				RevisionDiffBody body = new RevisionDiffBody(lastSent.revision,
						record.revision);

				debugMessage(DEBUG_SEND, "send diff to {}: {} {} {} body {}",
						state.key, target.node, lastSent.sequenceNumber,
						record.sequenceNumber, body);

				send(state, new Diff(target.node, lastSent.sequenceNumber,
						record.sequenceNumber, body));
			}

			state.connectionState.lastSent.put(target.node, record);
			break;
		}
	}

	private NodeState accept(NodeKey origin) {
		NodeState state = state(origin);

		NodeState defaultState = directlyConnectedStates.get(origin.id);

		if (state != defaultState) {
			expect(defaultState.head.sequenceNumber == 0);

			state.connectionState = defaultState.connectionState;

			directlyConnectedStates.put(origin.id, state);
		}

		return state;
	}

	private void acceptSync(NodeKey origin) {
		debugMessage(DEBUG_RECEIVE, "sync from {}", origin);

		NodeState state = accept(origin);

		if (!state.connectionState.gotSync) {
			state.connectionState.gotSync = true;
			if (state.syncListeners != null) {
				for (Runnable r : state.syncListeners) {
					r.run();
				}
			}
			state.syncListeners = null;

			sendNext();
		}
	}

	private void acceptHello(NodeKey origin) {
		debugMessage(DEBUG_HELLO, "hello from {} ", origin);

		NodeState state = accept(origin);

		state.connectionState.gotHello = true;
		sendNext(state);
	}

	private void acceptDiff(NodeKey origin, long startSequenceNumber,
			long endSequenceNumber, DiffBody body) {
		NodeState state = state(origin);

		Record head = head(state);

		debugMessage(DEBUG_RECEIVE, "accept diff {} {} {} head {} body {}",
				origin, startSequenceNumber, endSequenceNumber, head, body);

		if (startSequenceNumber <= head.sequenceNumber) {
			Record record = head;
			while (record != null && endSequenceNumber != record.sequenceNumber
					&& startSequenceNumber < record.sequenceNumber) {
				record = record.previous.get();
			}

			if (record != null) {
				if (endSequenceNumber == record.sequenceNumber
						|| startSequenceNumber == record.sequenceNumber) {
					acceptRevision(state, endSequenceNumber,
							body.apply(this, record.revision));
				} else {
					throw new RuntimeException("missed a diff");
				}
			} else {
				throw new RuntimeException("obsolete diff");
			}
		} else {
			throw new RuntimeException("missed a diff");
		}
	}

	private void acceptRevision(NodeState state, long sequenceNumber,
			Revision revision) {
		insertRevision(state, sequenceNumber, revision, null);

		acceptAck(localNode.key, nextLocalSequenceNumber++, state.key,
				sequenceNumber);
	}

	private void insertRevision(NodeState state, long sequenceNumber,
			Revision revision, Record merged) {
		Record record = head(state);
		debugMessage(DEBUG_STATE, "insertRevision: record: {}",
				record.hashCode());

		while (sequenceNumber < record.sequenceNumber) {
			record = record.previous.get();
		}

		if (sequenceNumber != record.sequenceNumber) {
			Record next = record.next;
			Record newRecord = new Record(state.key, revision, sequenceNumber,
					merged);
			record.next = newRecord;
			newRecord.previous = new WeakReference<Record>(record);
			debugMessage(DEBUG_STATE, "link {} {} to {} {}",
					record.sequenceNumber, record.hashCode(),
					newRecord.sequenceNumber, newRecord.hashCode());

			if (next != null) {
				debugMessage(DEBUG_STATE, "link {} {} to {} {}",
						newRecord.sequenceNumber, newRecord.hashCode(),
						+next.sequenceNumber, next.hashCode());

				newRecord.next = next;
				next.previous = new WeakReference<Record>(newRecord);
			}

			record = newRecord;
		}

		if (state.head.sequenceNumber < record.sequenceNumber) {
			state.head = record;

			if (state == localNode) {
				debugMessage(DEBUG_STATE, "notify listeners {}", head());

				// tell everyone we have updates!
				// TODO: don't notify people twice for each update.
				for (Runnable listener : listeners) {
					listener.run();
				}
			}
		}

		debugMessage(DEBUG_STATE,
				"insertRevision state.key: {}, sequenceNo: {}, record: {}",
				state.key, sequenceNumber, record.hashCode());

	}

	private Revision merge(Record base, Record head, Record fork,
			NodeKey headKey, NodeKey forkKey) {
		MyConflictResolver resolver = new MyConflictResolver(headKey.id,
				forkKey.id, conflictResolver);

		Revision result = head.revision;
		Record record = base.next;
		while (base != fork) {
			if (record.merged == null) {
				result = base.revision.merge(result, record.revision, resolver,
						foreignKeyResolver);
			}

			base = record;
			record = record.next;
		}

		return result;
	}

	private void acceptAck(NodeKey acknowledger,
			long acknowledgerSequenceNumber, NodeKey diffOrigin,
			long diffSequenceNumber) {
		debugMessage(DEBUG_RECEIVE, "accept ack {} {} diff {} {}",
				acknowledger, acknowledgerSequenceNumber, diffOrigin,
				diffSequenceNumber);

		NodeState state = state(acknowledger);

		Record record = state.acknowledged.get(diffOrigin);

		if (record.sequenceNumber < diffSequenceNumber) {
			Record base = record;
			while (record != null && record.sequenceNumber < diffSequenceNumber) {
				record = record.next;
			}

			if (record != null && record.sequenceNumber == diffSequenceNumber) {
				if (record.merged == null) {
					insertRevision(
							state,
							acknowledgerSequenceNumber,
							merge(base, state.head, record, acknowledger,
									diffOrigin), record);
				}

				state.acknowledged.put(diffOrigin, record);

				if (record.merged == null) {
					acceptAck(localNode.key, nextLocalSequenceNumber++,
							acknowledger, acknowledgerSequenceNumber);
				}
			} else {
				throw new RuntimeException("missed a diff");
			}
		} else {
			// obsolete ack -- ignore
		}

		sendNext();
	}

	private static class NodeState {
		public final NodeKey key;
		public Record head;
		public final Map<NodeKey, Record> acknowledged = new HashMap<NodeKey, Record>();
		public Set<Runnable> syncListeners = new HashSet<Runnable>();
		public ConnectionState connectionState;

		public NodeState(NodeKey key) {
			this.key = key;
		}
	}

	private static class ConnectionState {
		public final Map<NodeKey, Record> lastSent = new HashMap<NodeKey, Record>();
		public boolean readyToReceive;
		public boolean sentHello;
		public boolean gotHello;
		public boolean sentSync;
		public boolean gotSync;
	}

	private static class Record {
		public final NodeKey node;
		public final Revision revision;
		public final long sequenceNumber;
		public final Record merged;
		public WeakReference<Record> previous;
		public Record next;

		public Record(NodeKey node, Revision revision, long sequenceNumber,
				Record merged) {
			this.node = node;
			this.revision = revision;
			this.sequenceNumber = sequenceNumber;
			this.merged = merged;
		}
	}

	private interface Message extends Writable, Readable {
		public void deliver(NodeID source, EpidemicServer server);
	}

	// public for deserialization
	public static class Ack implements Message {
		private NodeKey acknowledger;
		private long acknowledgerSequenceNumber;
		private NodeKey diffOrigin;
		private long diffSequenceNumber;

		private Ack(NodeKey acknowledger, long acknowledgerSequenceNumber,
				NodeKey diffOrigin, long diffSequenceNumber) {
			this.acknowledger = acknowledger;
			this.acknowledgerSequenceNumber = acknowledgerSequenceNumber;
			this.diffOrigin = diffOrigin;
			this.diffSequenceNumber = diffSequenceNumber;
		}

		// for deserialization
		public Ack() {
		}

		@Override
		public void writeTo(WriteContext context) throws IOException {
			StreamUtil.writeString(context.out, acknowledger.asString());
			StreamUtil.writeLong(context.out, acknowledgerSequenceNumber);
			StreamUtil.writeString(context.out, diffOrigin.asString());
			StreamUtil.writeLong(context.out, diffSequenceNumber);
		}

		@Override
		public void readFrom(ReadContext context) throws IOException {
			acknowledger = new NodeKey(StreamUtil.readString(context.in));
			acknowledgerSequenceNumber = StreamUtil.readLong(context.in);
			diffOrigin = new NodeKey(StreamUtil.readString(context.in));
			diffSequenceNumber = StreamUtil.readLong(context.in);
		}

		@Override
		public void deliver(NodeID source, EpidemicServer server) {
			server.debugMessage(DEBUG_RECEIVE, "ack from {}", source);

			server.acceptAck(acknowledger, acknowledgerSequenceNumber,
					diffOrigin, diffSequenceNumber);
		}
	}

	// public for deserialization
	public static class Diff implements Message {
		private NodeKey origin;
		private long startSequenceNumber;
		private long endSequenceNumber;
		private DiffBody body;

		private Diff(NodeKey origin, long startSequenceNumber,
				long endSequenceNumber, DiffBody body) {
			this.origin = origin;
			this.startSequenceNumber = startSequenceNumber;
			this.endSequenceNumber = endSequenceNumber;
			this.body = body;
		}

		// for deserialization
		public Diff() {
		}

		@Override
		public void writeTo(WriteContext context) throws IOException {
			StreamUtil.writeString(context.out, origin.asString());
			StreamUtil.writeLong(context.out, startSequenceNumber);
			StreamUtil.writeLong(context.out, endSequenceNumber);
			((Writable) body).writeTo(context);
		}

		@Override
		public void readFrom(ReadContext context) throws IOException {
			origin = new NodeKey(StreamUtil.readString(context.in));
			startSequenceNumber = StreamUtil.readLong(context.in);
			endSequenceNumber = StreamUtil.readLong(context.in);
			BufferDiffBody list = new BufferDiffBody();
			list.readFrom(context);
			body = list;
		}

		@Override
		public void deliver(NodeID source, EpidemicServer server) {
			server.debugMessage(DEBUG_RECEIVE, "diff from {}", source);

			server.acceptDiff(origin, startSequenceNumber, endSequenceNumber,
					body);
		}

		@Override
		public String toString() {
			return "diff[" + body + "]";
		}
	}

	private static abstract class UUIDMessage implements Message {
		public UUID instance;

		public UUIDMessage() {
		}

		public UUIDMessage(UUID instance) {
			this.instance = instance;
		}

		@Override
		public void writeTo(WriteContext context) throws IOException {
			StreamUtil.writeString(context.out, instance.toString());
		}

		@Override
		public void readFrom(ReadContext context) throws IOException {
			instance = UUID.fromString(StreamUtil.readString(context.in));
		}
	}

	// public for deserialization
	public static class Hello extends UUIDMessage {
		public Hello() {
		}

		public Hello(UUID instance) {
			super(instance);
		}

		@Override
		public void deliver(NodeID source, EpidemicServer server) {
			server.acceptHello(new NodeKey(source, instance));
		}
	}

	// public for deserialization
	public static class Sync extends UUIDMessage {
		public Sync() {
		}

		public Sync(UUID instance) {
			super(instance);
		}

		@Override
		public void deliver(NodeID source, EpidemicServer server) {
			server.acceptSync(new NodeKey(source, instance));
		}
	}

	private static interface DiffBody {
		public Revision apply(EpidemicServer server, Revision base);
	}

	private static class RevisionDiffBody implements DiffBody, Writable {
		public final Revision base;
		public final Revision fork;

		public RevisionDiffBody(Revision base, Revision fork) {
			this.base = base;
			this.fork = fork;
		}

		@Override
		public Revision apply(EpidemicServer server, Revision base) {
			return fork;
		}

		@Override
		public void writeTo(WriteContext context) throws IOException {
			DiffResult result = base.diff(fork, true);
			Table table = null;
			int depth = 0;
			while (true) {
				DiffResult.Type type = result.next();
				switch (type) {
				case End:
					context.out.write(End);
					return;

				case Descend: {
					++depth;
					context.out.write(Descend);
				}
					break;

				case Ascend: {
					--depth;
					context.out.write(Ascend);
				}
					break;

				case Key: {
					Object forkKey = result.fork();
					if (forkKey != null) {
						if (depth == 0)
							table = (Table) forkKey;

						if (Constants.serializable(table, forkKey, depth)) {
							context.out.write(Key);
							Protocol.write(context, forkKey);
						} else {
							result.skip();
						}
					} else {
						Object baseKey = result.base();

						if (depth == 0)
							table = (Table) baseKey;

						if (Constants.serializable(table, baseKey, depth)) {
							context.out.write(Delete);
							Protocol.write(context, baseKey);
						}
						result.skip();
					}
				}
					break;

				case Value: {
					context.out.write(Insert);
					Protocol.write(context, result.fork());
				}
					break;

				default:
					throw new RuntimeException("unexpected result type: "
							+ type);
				}
			}
		}

		@Override
		public String toString() {
			// todo: reduce code duplication between this and the writeTo method
			return Util.toString(base, fork);
		}
	}

	private static class BufferDiffBody implements DiffBody, Readable {
		public BufferOutputStream buffer;
		public InputStream input;

		@Override
		public Revision apply(EpidemicServer server, Revision base) {
			RevisionBuilder builder = base.builder();
			final int MaxDepth = 16;
			Object[] path = new Object[MaxDepth];
			int depth = 0;
			boolean visitedColumn = true;

			try {
				InputStream in;
				if (input == null) {
					in = new ByteArrayInputStream(buffer.getBuffer(), 0,
							buffer.size());
				} else {
					in = input;
					in.reset();
				}
				ReadContext readContext = new ReadContext(in);

				while (true) {
					int flag = in.read();
					switch (flag) {
					case End:
						return builder.commit(server.foreignKeyResolver);

					case Descend:
						visitedColumn = true;
						++depth;
						break;

					case Ascend:
						if (!visitedColumn) {
							visitedColumn = true;
							builder.insert(DuplicateKeyResolution.Overwrite,
									path, 0, depth + 1);
						}

						path[depth--] = null;
						break;

					case Key:
						if (!visitedColumn) {
							builder.insert(DuplicateKeyResolution.Overwrite,
									path, 0, depth + 1);
						} else {
							visitedColumn = false;
						}

						path[depth] = Protocol.read(readContext);
						break;

					case Delete:
						visitedColumn = true;
						path[depth] = Protocol.read(readContext);
						builder.delete(path, 0, depth + 1);
						break;

					case Insert:
						visitedColumn = true;
						path[depth + 1] = Protocol.read(readContext);
						builder.insert(DuplicateKeyResolution.Overwrite, path,
								0, depth + 2);
						break;

					default:
						throw new RuntimeException("unexpected flag: " + flag);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void readFrom(ReadContext context) throws IOException {
			if (context.in.markSupported()) {
				context.in.mark(Integer.MAX_VALUE);
				input = context.in;
			} else {
				buffer = new BufferOutputStream();
				WriteContext writeContext = new WriteContext(buffer);
				while (true) {
					int flag = context.in.read();
					switch (flag) {
					case -1:
						throw new EOFException();

					case End:
						buffer.write(flag);
						return;

					case Descend:
					case Ascend:
						buffer.write(flag);
						break;

					case Key:
					case Delete:
					case Insert:
						buffer.write(flag);
						Protocol.write(writeContext, Protocol.read(context));
						break;

					default:
						throw new RuntimeException("unexpected flag: " + flag);
					}
				}
			}
		}

		@Override
		public String toString() {
			// todo: reduce code duplication between this and the apply method
			StringBuilder sb = new StringBuilder();
			final int MaxDepth = 16;
			Object[] path = new Object[MaxDepth];
			int depth = 0;
			boolean visitedColumn = true;

			try {
				InputStream in;
				if (input == null) {
					in = new ByteArrayInputStream(buffer.getBuffer(), 0,
							buffer.size());
				} else {
					in = input;
					in.reset();
				}
				ReadContext readContext = new ReadContext(in);

				while (true) {
					int flag = in.read();
					switch (flag) {
					case End:
						return sb.toString();

					case Descend:
						visitedColumn = true;
						++depth;
						break;

					case Ascend:
						if (!visitedColumn) {
							visitedColumn = true;
							sb.append("insert");
							sb.append(Util.toString(path, 0, depth + 1));
							sb.append("\n");
						}

						path[depth--] = null;
						break;

					case Key:
						if (!visitedColumn) {
							sb.append("insert");
							sb.append(Util.toString(path, 0, depth + 1));
							sb.append("\n");
						} else {
							visitedColumn = false;
						}

						path[depth] = Protocol.read(readContext);
						break;

					case Delete:
						visitedColumn = true;
						path[depth] = Protocol.read(readContext);
						sb.append("delete");
						sb.append(Util.toString(path, 0, depth + 1));
						sb.append("\n");
						break;

					case Insert:
						visitedColumn = true;
						path[depth + 1] = Protocol.read(readContext);
						sb.append("insert");
						sb.append(Util.toString(path, 0, depth + 2));
						sb.append("\n");
						break;

					default:
						throw new RuntimeException("unexpected flag: " + flag);
					}
				}
			} catch (IOException e) {
				// shouldn't be possible, since we're reading from a byte array
				throw new RuntimeException(e);
			}
		}
	}

	private static class NodeKey implements Comparable<NodeKey>, Stringable {
		public final NodeID id;
		public final UUID instance;

		public NodeKey(NodeID id, UUID instance) {
			this.id = id;
			this.instance = instance;
		}

		public NodeKey(String string) {
			int index = string.indexOf(':');
			this.id = new NodeID(string.substring(index + 1));
			this.instance = UUID.fromString(string.substring(0, index));
		}

		@Override
		public int hashCode() {
			return id.hashCode() ^ instance.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof NodeKey && compareTo((NodeKey) o) == 0;
		}

		@Override
		public int compareTo(NodeKey o) {
			int d = id.compareTo(o.id);
			if (d != 0) {
				return d;
			}

			return instance.compareTo(o.instance);
		}

		@Override
		public String toString() {
			return "nodeKey[" + id + " " + instance.toString().substring(0, 8)
					+ "]";
		}

		@Override
		public String asString() {
			return instance + ":" + id.asString();
		}
	}

	private static class MyConflictResolver implements ConflictResolver {
		private final NodeID leftNode;
		private final NodeID rightNode;
		private final NodeConflictResolver resolver;

		public MyConflictResolver(NodeID leftNode, NodeID rightNode,
				NodeConflictResolver resolver) {
			this.leftNode = leftNode;
			this.rightNode = rightNode;
			this.resolver = resolver;
		}

		@Override
		public Object resolveConflict(Table table, Column column,
				Object[] primaryKeyValues, Object baseValue, Object leftValue,
				Object rightValue) {
			return resolver.resolveConflict(leftNode, rightNode, table, column,
					primaryKeyValues, baseValue, leftValue, rightValue);
		}
	}
}
