package org.truechain.message;


import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.truechain.core.Sha256Hash;
import org.truechain.core.exception.ProtocolException;
import org.truechain.network.NetworkParameters;
import org.truechain.utils.Base16;
import org.truechain.utils.Utils;

public class DefaultMessageSerializer extends MessageSerializer {

	private static final Logger log = LoggerFactory.getLogger(DefaultMessageSerializer.class);
	
	private final NetworkParameters network;
	
	private static final Map<Class<? extends Message>, String> COMMANDS = new HashMap<Class<? extends Message>, String>();

    static {
    	COMMANDS.put(VersionMessage.class, "version");
    }

	public DefaultMessageSerializer(NetworkParameters network) {
		this.network = network;
	}

	@Override
    public void serialize(String command, byte[] message, OutputStream out) throws IOException {
        byte[] header = new byte[4 + COMMAND_LEN + 4 + 4 /* checksum */];
        Utils.uint32ToByteArrayBE(network.getPacketMagic(), header, 0);

        // The header array is initialized to zero by Java so we don't have to worry about
        // NULL terminating the string here.
        for (int i = 0; i < command.length() && i < COMMAND_LEN; i++) {
            header[4 + i] = (byte) (command.codePointAt(i) & 0xFF);
        }

        Utils.uint32ToByteArrayLE(message.length, header, 4 + COMMAND_LEN);

        byte[] hash = Sha256Hash.hashTwice(message);
        System.arraycopy(hash, 0, header, 4 + COMMAND_LEN + 4, 4);
        out.write(header);
        out.write(message);

        if (log.isDebugEnabled())
            log.debug("Sending {} message: {}", command, Base16.encode(header) + Base16.encode(message));
    }
	
	@Override
    public void serialize(Message message, OutputStream out) throws IOException {
        String name = COMMANDS.get(message.getClass());
        if (name == null) {
            throw new Error("DefaultSerializer doesn't currently know how to serialize " + message.getClass());
        }
        serialize(name, message.baseSerialize(), out);
    }
	
	@Override
	public Message deserialize(ByteBuffer in) throws ProtocolException, IOException, UnsupportedOperationException {
		// protocol message has the following format.
        //
        //   - 4 byte magic number: 0xfabfb5da for the testnet or
        //                          0xf9beb4d9 for production
        //   - 12 byte command in ASCII
        //   - 4 byte payload size
        //   - 4 byte checksum
        //   - Payload data
        //
        // The checksum is the first 4 bytes of a SHA256 hash of the message payload. It isn't
        // present for all messages, notably, the first one on a connection.
        //
        // Bitcoin Core ignores garbage before the magic header bytes. We have to do the same because
        // sometimes it sends us stuff that isn't part of any message.
        seekPastMagicBytes(in);
        MessagePacketHeader header = new MessagePacketHeader(in);
        // Now try to read the whole message.
        return deserializePayload(header, in);
	}

	@Override
	public MessagePacketHeader deserializeHeader(ByteBuffer in) throws ProtocolException, IOException, UnsupportedOperationException {
		return new MessagePacketHeader(in);
	}

	@Override
	public Message deserializePayload(MessagePacketHeader header, ByteBuffer in)
			throws ProtocolException, BufferUnderflowException, UnsupportedOperationException {
		byte[] payloadBytes = new byte[header.size];
        in.get(payloadBytes, 0, header.size);

        // Verify the checksum.
        byte[] hash;
        hash = Sha256Hash.hashTwice(payloadBytes);
        if (header.checksum[0] != hash[0] || header.checksum[1] != hash[1] ||
                header.checksum[2] != hash[2] || header.checksum[3] != hash[3]) {
            throw new ProtocolException("Checksum failed to verify, actual " +
            		Base16.encode(hash) + " vs " + Base16.encode(header.checksum));
        }

        if (log.isDebugEnabled()) {
            log.debug("Received {} byte '{}' message: {}", header.size, header.command,
                    Base16.encode(payloadBytes));
        }

        try {
            return makeMessage(header.command, header.size, payloadBytes, hash, header.checksum);
        } catch (Exception e) {
            throw new ProtocolException("Error deserializing message " + Base16.encode(payloadBytes) + "\n", e);
        }
	}

	private Message makeMessage(String command, int size, byte[] payloadBytes, byte[] hash, byte[] checksum) {
		Message message;
        if (command.equals("version")) {
        	message = new VersionMessage(network, payloadBytes);
        } else if (command.equals("verack")) {
        	message = new VersionAck(network, payloadBytes);
        } else {
        	log.warn("No support for deserializing message with name {}", command);
        	message = new UnknownMessage(network, command, payloadBytes);
        }
		return message;
	}

	@Override
	public void seekPastMagicBytes(ByteBuffer in) throws BufferUnderflowException {
		int magicCursor = 3;  // Which byte of the magic we're looking for currently.
        while (true) {
            byte b = in.get();
            // We're looking for a run of bytes that is the same as the packet magic but we want to ignore partial
            // magics that aren't complete. So we keep track of where we're up to with magicCursor.
            byte expectedByte = (byte)(0xFF & network.getPacketMagic() >>> (magicCursor * 8));
            if (b == expectedByte) {
                magicCursor--;
                if (magicCursor < 0) {
                    // We found the magic sequence.
                    return;
                } else {
                    // We still have further to go to find the next message.
                }
            } else {
                magicCursor = 3;
            }
        }
	}

	@Override
	public boolean isParseRetainMode() {
		return false;
	}

}
