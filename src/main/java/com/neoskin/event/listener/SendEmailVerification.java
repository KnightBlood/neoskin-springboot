package com.neoskin.event.listener;

import com.neoskin.db.UserRepository;
import com.neoskin.service.Mailer;
import com.neoskin.service.VerificationMail;

/**
 * {@code auth.registration.completed}（及 {@code UserRegistered}）监听者：
 * 发送邮箱验证邮件（源 {@code App\Listeners\SendEmailVerification}）。
 *
 * <p>源监听者自己组装签名链接与文案；Java 侧把组装留在调用方
 * （{@link VerificationMail} 已有现成方法），本类只负责投递，便于事件参数精简。</p>
 */
public final class SendEmailVerification {

    /** 投递验证邮件。 */
    public void handle(Mailer mailer, UserRepository.UserRow user, String subject, String body)
            throws Exception {
        mailer.send(user.email(), subject, body);
    }
}
