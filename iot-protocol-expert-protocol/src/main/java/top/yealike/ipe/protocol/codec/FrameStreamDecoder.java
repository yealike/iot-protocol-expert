package top.yealike.ipe.protocol.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 处理 TCP 粘包/半包。起始符 0x68，长度 1 字节，整帧 = 长度 + 4。
 * 非法起始符逐字节跳过，等待更多数据时不消费。
 */
public class FrameStreamDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(FrameStreamDecoder.class);
    private static final int MAX_DATA_LENGTH = 204;

    private final byte[] key;

    public FrameStreamDecoder(byte[] key) {
        this.key = key;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            if (in.readableBytes() < 2) {
                return;
            }
            in.markReaderIndex();
            int start = in.readUnsignedByte();
            if (start != FrameCodec.START) {
                in.resetReaderIndex();
                in.skipBytes(1);
                continue;
            }
            int length = in.readUnsignedByte();
            if (length < 4 || length > MAX_DATA_LENGTH) {
                in.resetReaderIndex();
                in.skipBytes(1);
                continue;
            }
            int total = length + 4;
            if (in.readableBytes() < total - 2) {
                in.resetReaderIndex();
                return;
            }
            byte[] raw = new byte[total];
            raw[0] = (byte) FrameCodec.START;
            raw[1] = (byte) length;
            in.readBytes(raw, 2, total - 2);
            try {
                out.add(new InboundFrame(FrameCodec.decode(raw, key), FrameCodec.hex(raw)));
            } catch (ProtocolException e) {
                log.warn("丢弃非法帧 {}: {}", FrameCodec.hex(raw), e.getMessage());
            }
        }
    }
}