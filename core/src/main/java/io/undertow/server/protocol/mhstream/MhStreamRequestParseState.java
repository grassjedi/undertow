/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.protocol.mhstream;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Map;

import io.undertow.server.BasicSSLSessionInfo;
import io.undertow.util.HttpString;

/**
 * @author Stuart Douglas
 */
class MhStreamRequestParseState {


    public static final int START = 0;
    public static final int READ_LENGTH_HEADER = 16;
    public static final int READ_REQUEST_BODY = 32;
    public static final int CONSTRUCT_REQUEST = 48;
    public static final int COMPLETE = 64;

    public int payloadLength = -1;
    public byte[] buffer;
    public int bufferOffs = 0;
    public String tqFunctionName = null;

    int state;
    String remoteAddress;
    int remotePort = -1;
    int serverPort = 80;
    String serverAddress;


    MhStreamRequestParseState() {

    }

    public void reset() {
        this.bufferOffs = 0;
        this.tqFunctionName = null;
        this.payloadLength = 0;
    }

    public boolean isComplete() {
        return state == COMPLETE;
    }

    InetSocketAddress createPeerAddress() {
        if (remoteAddress == null) {
            return null;
        }
        int port = remotePort > 0 ? remotePort : 0;
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            return new InetSocketAddress(address, port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    InetSocketAddress createDestinationAddress() {
        if (serverAddress == null) {
            return null;
        }
        return InetSocketAddress.createUnresolved(serverAddress, serverPort);
    }
}
