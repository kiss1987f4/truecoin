package org.truechain.network;

import org.truechain.message.DefaultMessageSerializer;
import org.truechain.message.MessageSerializer;

public class TestNetworkParameters extends NetworkParameters {

	private static TestNetworkParameters instance;
    public static synchronized TestNetworkParameters get() {
        if (instance == null) {
            instance = new TestNetworkParameters();
        }
        return instance;
    }
    
	@Override
	public int getProtocolVersionNum(ProtocolVersion version) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public MessageSerializer getSerializer(boolean parseRetain) {
		return new DefaultMessageSerializer(this);
	}

}
