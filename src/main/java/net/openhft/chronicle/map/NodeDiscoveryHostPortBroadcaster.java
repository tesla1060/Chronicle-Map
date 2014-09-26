package net.openhft.chronicle.map;

import net.openhft.lang.collection.ATSDirectBitSet;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.Bytes;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.openhft.chronicle.map.NodeDiscoveryHostPortBroadcaster.BOOTSTRAP_BYTES;

/**
 * @author Rob Austin.
 */
public class NodeDiscoveryHostPortBroadcaster extends UdpChannelReplicator {

    private static final byte UNUSED = (byte) -1;

    static final ByteBufferBytes BOOTSTRAP_BYTES;


    static {
        final String BOOTSTRAP = "BOOTSTRAP";
        BOOTSTRAP_BYTES = new ByteBufferBytes(ByteBuffer.allocate(2 + BOOTSTRAP.length()));
        BOOTSTRAP_BYTES.write((short) BOOTSTRAP.length());
        BOOTSTRAP_BYTES.append(BOOTSTRAP);
    }

    /**
     * @param replicationConfig
     * @param externalizable
     * @throws java.io.IOException
     */
    NodeDiscoveryHostPortBroadcaster(
            @NotNull final UdpReplicationConfig replicationConfig,
            final int serializedEntrySize,
            final BytesExternalizable externalizable)
            throws IOException {

        super(replicationConfig, serializedEntrySize, UNUSED);

        final UdpSocketChannelEntryWriter writer = new UdpSocketChannelEntryWriter(1024, externalizable,
                this);
        final UdpSocketChannelEntryReader reader = new UdpSocketChannelEntryReader(1024, externalizable,
                this);


        setReader(reader);

        setWriter(writer);

        start();
    }

    private static class UdpSocketChannelEntryReader implements EntryReader {


        private final ByteBuffer in;
        private final ByteBufferBytes out;
        private final BytesExternalizable externalizable;
        private Replica.ModificationNotifier modificationNotifier;

        /**
         * @param serializedEntrySize  the maximum size of an entry include the meta data
         * @param externalizable       supports reading and writing serialize entries
         * @param modificationNotifier
         */
        public UdpSocketChannelEntryReader(final int serializedEntrySize,
                                           @NotNull final BytesExternalizable externalizable,
                                           @NotNull final Replica.ModificationNotifier modificationNotifier) {
            this.modificationNotifier = modificationNotifier;
            // we make the buffer twice as large just to give ourselves headroom
            in = ByteBuffer.allocateDirect(serializedEntrySize * 2);

            out = new ByteBufferBytes(in);
            out.limit(0);
            in.clear();
            this.externalizable = externalizable;
        }

        /**
         * reads entries from the socket till it is empty
         *
         * @param socketChannel the socketChannel that we will read from
         * @throws IOException
         * @throws InterruptedException
         */
        public void readAll(@NotNull final DatagramChannel socketChannel) throws IOException,
                InterruptedException {

            out.clear();
            in.clear();

            socketChannel.receive(in);

            final int bytesRead = in.position();

            if (bytesRead < SIZE_OF_SHORT + SIZE_OF_SHORT)
                return;

            out.limit(in.position());

            final short invertedSize = out.readShort();
            final int size = out.readUnsignedShort();

            // check the the first 4 bytes are the inverted len followed by the len
            // we do this to check that this is a valid start of entry, otherwise we throw it away
            if (((short) ~size) != invertedSize)
                return;

            if (out.remaining() != size)
                return;

            externalizable.readExternalBytes(out);
        }


    }

    public static class UdpSocketChannelEntryWriter implements EntryWriter {


        private final ByteBuffer out;
        private final ByteBufferBytes in;


        @NotNull
        private final BytesExternalizable externalizable;
        private UdpChannelReplicator udpReplicator;

        public UdpSocketChannelEntryWriter(final int serializedEntrySize,
                                           @NotNull final BytesExternalizable externalizable,
                                           @NotNull final UdpChannelReplicator udpReplicator) {

            this.externalizable = externalizable;
            this.udpReplicator = udpReplicator;

            // we make the buffer twice as large just to give ourselves headroom
            out = ByteBuffer.allocateDirect(serializedEntrySize * 2);
            in = new ByteBufferBytes(out);


        }


        /**
         * writes all the entries that have changed, to the tcp socket
         *
         * @param socketChannel
         * @param modificationIterator
         * @throws InterruptedException
         * @throws java.io.IOException
         */

        /**
         * update that are throttled are rejected.
         *
         * @param socketChannel the socketChannel that we will write to
         * @throws InterruptedException
         * @throws IOException
         */
        public int writeAll(@NotNull final DatagramChannel socketChannel)
                throws InterruptedException, IOException {

            out.clear();
            in.clear();

            // skip the size inverted
            in.skip(SIZE_OF_SHORT);

            // skip the size
            in.skip(SIZE_OF_SHORT);

            long start = in.position();

            // writes the contents of
            externalizable.writeExternalBytes(in);

            long size = in.position() - start;


            // we'll write the size inverted at the start
            in.writeShort(0, ~((int) size));
            in.writeUnsignedShort(SIZE_OF_SHORT, (int) size);


            out.limit((int) in.position());

            udpReplicator.disableWrites();

            return socketChannel.write(out);


        }
    }

}


/**
 * supports reading and writing serialize entries
 */
interface BytesExternalizable {


    void writeExternalBytes(@NotNull Bytes destination);


    public void readExternalBytes(@NotNull Bytes source);

}

class RemoteNodes {


    private final Bytes activeIdentifiersBitSetBytes;
    private Set<InetSocketAddress> inetSocketAddresses;
    private final ATSDirectBitSet atsDirectBitSet;


    /**
     * @param activeIdentifiersBitSetBytes byte sof a bitset containing the known identifiers
     */
    RemoteNodes(final Bytes activeIdentifiersBitSetBytes) {
        this.inetSocketAddresses = new HashSet<InetSocketAddress>();
        this.activeIdentifiersBitSetBytes = activeIdentifiersBitSetBytes;
        this.atsDirectBitSet = new ATSDirectBitSet(this.activeIdentifiersBitSetBytes);
    }


    public Set<InetSocketAddress> inetSocketAddresses() {
        return inetSocketAddresses;
    }

    public Bytes activeIdentifierBytes() {
        return activeIdentifiersBitSetBytes;
    }

    public void add(InetSocketAddress inetSocketAddress) {
        inetSocketAddresses.add(inetSocketAddress);
    }

    public ATSDirectBitSet activeIdentifierBitSet() {
        return atsDirectBitSet;
    }
}

class BytesExternalizableImpl implements BytesExternalizable {

    private final RemoteNodes allNodes;

    private final AtomicBoolean bootstrapRequired = new AtomicBoolean();

    public void setModificationNotifier(Replica.ModificationNotifier modificationNotifier) {
        this.modificationNotifier = modificationNotifier;
    }

    Replica.ModificationNotifier modificationNotifier;

    public BytesExternalizableImpl(final RemoteNodes allNodes) {
        this.allNodes = allNodes;
    }

    private final AtomicBoolean modificationsComplete = new AtomicBoolean(true);

    /**
     * this is used to tell nodes that are connecting to us which host and ports are in our grid, along with
     * all the identifiers.
     *
     * @param destination a buffer the entry will be written to, the segment may reject this operation and add
     *                    zeroBytes, if the identifier in the entry did not match the maps local
     */
    @Override
    public void writeExternalBytes(@NotNull Bytes destination) {

        modificationsComplete.set(true);

        if (bootstrapRequired.getAndSet(false)) {
            //destination.write(BOOTSTRAP_BYTES);
            BOOTSTRAP_BYTES.clear();
            destination.write(BOOTSTRAP_BYTES);
            return;
        }

        final Set<InetSocketAddress> inetSocketAddresses = allNodes.inetSocketAddresses();

        // write the number of hosts and ports
        short count = (short) inetSocketAddresses.size();
        destination.writeUnsignedShort(count);

        // write all the host and ports
        for (InetSocketAddress inetSocketAddress : inetSocketAddresses) {
            destination.writeUTF(inetSocketAddress.getHostName());
            destination.writeInt(inetSocketAddress.getPort());
        }

        Bytes bytes = allNodes.activeIdentifierBytes();
        bytes.clear();

        // we will store the size in here
        destination.writeUnsignedShort((int) bytes.remaining());
        destination.write(bytes);

        if (!(modificationsComplete.get()))
            modificationNotifier.onChange();
    }

    @Override
    public void readExternalBytes(@NotNull Bytes source) {

        if (isBootstrap(source)) {
            System.out.println("received Bootstrap");
            onChange();
            return;
        }

        int count = source.readUnsignedShort();

        for (int i = 0; i < count; i++) {

            final String host = source.readUTF();
            final int port = source.readInt();

            System.out.println("received InetSocketAddress(" + host + "," + port + ")");
            allNodes.add(new InetSocketAddress(host, port));
        }

        // the number of bytes in the bitset
        int sizeInBytes = source.readUnsignedShort();
        if (sizeInBytes == 0) {
            System.out.println("received nothing..");
            return;
        }

        source.limit(source.position() + sizeInBytes);

        final ATSDirectBitSet sourceBitSet = new ATSDirectBitSet(source);

        final ATSDirectBitSet resultBitSet = allNodes.activeIdentifierBitSet();

        // merges the source into the destination and returns the destination
        orBitSets(sourceBitSet, resultBitSet);


        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < resultBitSet.size(); i++) {
            builder.append(resultBitSet.get(i) ? '1' : '0');
        }

        System.out.println("identifer bitset =" + builder);

    }

    private void onChange() {
        modificationsComplete.set(false);
        modificationNotifier.onChange();
    }


    /**
     * @param source
     * @return returns true if the UDP message contains the text 'BOOTSTRAP'
     */
    private boolean isBootstrap(Bytes source) {

        final long start = source.position();

        try {

            long size = BOOTSTRAP_BYTES.limit();

            if (size < source.remaining())
                return false;

            if (source.remaining() < size)
                return false;

            for (int i = 0; i < size; i++) {

                final byte actualByte = source.readByte(start + i);
                final byte expectedByte = BOOTSTRAP_BYTES.readByte(i);

                if (!(expectedByte == actualByte))
                    return false;
            }

            return true;
        } finally {
            source.position(start);
        }
    }

    /**
     * merges the source bitset into the destination bitset and returns the destination
     *
     * @param source
     * @param destination
     * @return
     */
    private ATSDirectBitSet orBitSets(@NotNull final ATSDirectBitSet source,
                                      @NotNull final ATSDirectBitSet destination) {

        source.clear();

        // merges the two bit-sets together via 'or'
        for (int i = (int) source.nextSetBit(0); i > 0;
             i = (int) source.nextSetBit(i + 1)) {
            try {
                destination.set(i);
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
        }

        return destination;
    }


    public void sendBootStrap() {
        bootstrapRequired.set(true);
        onChange();
    }

    public void add(InetSocketAddress interfaceAddress) {
        allNodes.add(interfaceAddress);
        onChange();
    }


    public void add(byte identifier) {
        allNodes.activeIdentifierBitSet().set(identifier);
        onChange();
    }

}