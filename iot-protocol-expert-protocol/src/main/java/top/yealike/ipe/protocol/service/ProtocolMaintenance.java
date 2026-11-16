package top.yealike.ipe.protocol.service;

import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.SettingFrames;
import top.yealike.ipe.protocol.netty.SessionRegistry;

/** 通道侧例行维护：每日对时。业务数据不在通道内保存。 */
@Component
public class ProtocolMaintenance {

    private final SessionRegistry sessions;

    public ProtocolMaintenance(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void dailyTimeSync() {
        for (String pile : sessions.onlinePiles()) {
            try {
                sessions.send(
                        pile,
                        FrameType.TIME_SYNC,
                        SettingFrames.encodeTimeSync(new SettingFrames.TimeSync(pile, LocalDateTime.now())));
            } catch (RuntimeException ignored) {
            }
        }
    }
}
