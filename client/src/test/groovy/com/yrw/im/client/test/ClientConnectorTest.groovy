package com.yrw.im.client.test

import com.google.protobuf.ByteString
import com.yim.im.client.Client
import com.yim.im.client.api.ClientMsgListener
import com.yim.im.client.handler.ClientConnectorHandler
import com.yim.im.client.handler.code.AesDecoder
import com.yim.im.client.handler.code.AesEncoder
import com.yim.im.client.service.ClientRestService
import com.yrw.im.common.code.MsgDecoder
import com.yrw.im.common.code.MsgEncoder
import com.yrw.im.common.domain.po.Relation
import com.yrw.im.common.util.Encryption
import com.yrw.im.common.util.IdWorker
import com.yrw.im.proto.generate.Ack
import com.yrw.im.proto.generate.Chat
import com.yrw.im.proto.generate.Internal
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.CharsetUtil
import spock.lang.Specification

import java.time.Duration

/**
 * Date: 2019-06-06
 * Time: 14:30
 * @author yrw
 */
class ClientConnectorTest extends Specification {

    def setup() {
        ClientConnectorHandler.setClientMsgListener(Mock(ClientMsgListener))
    }

    def "test get ack"() {
        given:
        def channel = new EmbeddedChannel()
        def clientMsgListener = Mock(ClientMsgListener)
        ClientConnectorHandler.setClientMsgListener(clientMsgListener)
        channel.pipeline()
                .addLast("MsgEncoder", Client.injector.getInstance(MsgEncoder.class))
                .addLast("AesEncoder", Client.injector.getInstance(AesEncoder.class))
                .addLast("MsgDecoder", Client.injector.getInstance(MsgDecoder.class))
                .addLast("AesDecoder", Client.injector.getInstance(AesDecoder.class))
                .addLast("ClientConnectorHandler", new ClientConnectorHandler())

        when:
        def delivered = Ack.AckMsg.newBuilder()
                .setVersion(1)
                .setId(IdWorker.genId())
                .setCreateTime(System.currentTimeMillis())
                .setFromId(123)
                .setDestId(456)
                .setMsgType(Ack.AckMsg.MsgType.DELIVERED)
                .setDestType(Ack.AckMsg.DestType.SINGLE)
                .setAckMsgId(1111112)
                .build()

        channel.writeInbound(delivered)

        then:
        1 * clientMsgListener.hasDelivered(1111112)
        0 * _

        when:
        def read = Ack.AckMsg.newBuilder()
                .mergeFrom(delivered)
                .setMsgType(Ack.AckMsg.MsgType.READ)
                .build()

        channel.writeInbound(read)

        then:
        1 * clientMsgListener.hasRead(1111112)
        0 * _
    }

    def "test get internal"() {
        given:
        def channel = new EmbeddedChannel()
        def handler = new ClientConnectorHandler()
        channel.pipeline()
                .addLast("MsgEncoder", Client.injector.getInstance(MsgEncoder.class))
                .addLast("AesEncoder", Client.injector.getInstance(AesEncoder.class))
                .addLast("MsgDecoder", Client.injector.getInstance(MsgDecoder.class))
                .addLast("AesDecoder", Client.injector.getInstance(AesDecoder.class))
                .addLast("ClientConnectorHandler", handler)

        def collector = handler.createCollector(Duration.ofSeconds(2))

        def internal = Internal.InternalMsg.newBuilder()
                .setVersion(1)
                .setId(IdWorker.genId())
                .setCreateTime(System.currentTimeMillis())
                .setFrom(Internal.InternalMsg.Module.CONNECTOR)
                .setDest(Internal.InternalMsg.Module.CLIENT)
                .setMsgType(Internal.InternalMsg.MsgType.ACK)
                .setMsgBody("1111112")
                .build()

        when:
        channel.writeInbound(internal)

        then:
        collector.getFuture().isDone()
        0 * _

        //todo: test unexpected msg
    }

    def "test get chat"() {
        given:
        def clientMsgListener = Mock(ClientMsgListener)
        ClientConnectorHandler.setClientMsgListener(clientMsgListener)

        def channel = new EmbeddedChannel()

        def r = new Relation()
        r.setEncryptKey("HvxZFa7B1dBlKwP7|9302073163544974")

        def clientRestService = Mock(ClientRestService) {
            relation(123, 456, "token") >> r
        }

        String[] keys = r.getEncryptKey().split("\\|")
        byte[] encodeBody = Encryption.encrypt(keys[0], keys[1], "hello".getBytes(CharsetUtil.UTF_8))

        channel.pipeline()
                .addLast("MsgEncoder", Client.injector.getInstance(MsgEncoder.class))
                .addLast("AesEncoder", Client.injector.getInstance(AesEncoder.class))
                .addLast("MsgDecoder", Client.injector.getInstance(MsgDecoder.class))
                .addLast("AesDecoder", new AesDecoder(clientRestService))
                .addLast("ClientConnectorHandler", new ClientConnectorHandler())

        def chat = Chat.ChatMsg.newBuilder()
                .setVersion(1)
                .setId(IdWorker.genId())
                .setCreateTime(System.currentTimeMillis())
                .setFromId(123)
                .setDestId(456)
                .setMsgType(Chat.ChatMsg.MsgType.TEXT)
                .setDestType(Chat.ChatMsg.DestType.SINGLE)
                .setMsgBody(ByteString.copyFrom(encodeBody))
                .setToken("token")
                .build()

        def decodedChat = Chat.ChatMsg.newBuilder().mergeFrom(chat)
                .setMsgBody(ByteString.copyFromUtf8("hello")).build()
        when:
        channel.writeInbound(chat)

        then:
        1 * clientMsgListener.read(decodedChat)
    }
}