package com.neoskin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neoskin.config.NeoSkinSettings;

/**
 * {@code Illuminate\Mail} 的最小移植（{@code Mail::to(...)->send(...)}）。
 *
 * <p>支持的 mailer（{@code config('mail.default')} / {@code MAIL_MAILER}）：</p>
 * <ul>
 *   <li>{@code smtp}（默认）：按 {@code MAIL_*} 直连 SMTP，失败抛异常；</li>
 *   <li>{@code log}：把收件人/主题/正文写进应用日志（源把整封 MIME 写日志）；</li>
 *   <li>{@code array}：只收集不发送（源用于测试）。</li>
 * </ul>
 *
 * <p>其他值（如 {@code sendmail}、{@code mailgun} 等）按 SMTP 处理，源则依赖对应的
 * Laravel transport；这是已记录的偏差。</p>
 */
public final class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final NeoSkinSettings settings;

    public Mailer(NeoSkinSettings settings) {
        this.settings = settings;
    }

    /** {@code config('mail.default')}；未配置时按 smtp。 */
    public String mailerName() {
        NeoSkinSettings.Mail mail = settings == null ? null : settings.getMail();
        String name = mail == null ? null : mail.getMailer();

        return name == null || name.isBlank() ? "smtp" : name.trim().toLowerCase();
    }

    /**
     * 发送一封 HTML 邮件。
     *
     * @throws Exception SMTP 未配置或发送失败（log/array 不抛）
     */
    public void send(String to, String subject, String body) throws Exception {
        NeoSkinSettings.Mail mail = settings == null ? null : settings.getMail();

        switch (mailerName()) {
            case "log" -> {
                log.info("Mail (log mailer) to={} subject={} body={}", to, subject, body);
                return;
            }
            case "array" -> {
                return;
            }
            default -> sendSmtp(mail, to, subject, body);
        }
    }

    private static void sendSmtp(NeoSkinSettings.Mail mail, String to, String subject,
            String body) throws Exception {
        if (mail == null || mail.getHost() == null || mail.getHost().isBlank()) {
            throw new IllegalStateException("SMTP host is not configured");
        }

        java.util.Properties props = new java.util.Properties();
        props.put("mail.smtp.host", mail.getHost());
        props.put("mail.smtp.port", String.valueOf(mail.getPort()));
        props.put("mail.smtp.auth", mail.getUsername() != null && !mail.getUsername().isBlank());
        String encryption = mail.getEncryption() == null ? "" : mail.getEncryption().toLowerCase();
        if ("ssl".equals(encryption) || "tls".equals(encryption)) {
            props.put("mail.smtp.ssl.enable", true);
        } else {
            props.put("mail.smtp.starttls.enable", true);
        }

        jakarta.mail.Authenticator authenticator = null;
        final String user = mail.getUsername();
        final String pass = mail.getPassword();
        if (user != null && !user.isBlank()) {
            authenticator = new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(user, pass == null ? "" : pass);
                }
            };
        }

        jakarta.mail.Session session = jakarta.mail.Session.getInstance(props, authenticator);
        jakarta.mail.internet.MimeMessage msg = new jakarta.mail.internet.MimeMessage(session);

        String fromAddress = mail.getFromAddress() == null || mail.getFromAddress().isBlank()
                ? "noreply@localhost" : mail.getFromAddress();
        String fromName = mail.getFromName() == null ? "" : mail.getFromName();
        msg.setFrom(fromName.isBlank()
                ? new jakarta.mail.internet.InternetAddress(fromAddress)
                : new jakarta.mail.internet.InternetAddress(fromAddress, fromName));
        msg.setRecipients(jakarta.mail.Message.RecipientType.TO,
                jakarta.mail.internet.InternetAddress.parse(to, false));
        msg.setSubject(jakarta.mail.internet.MimeUtility.encodeText(subject, "UTF-8", "B"));
        msg.setContent(body, "text/html; charset=utf-8");
        // EmailVerification::headers() / ForgotPassword：Auto-Submitted: auto-generated
        msg.setHeader("Auto-Submitted", "auto-generated");
        msg.saveChanges();

        jakarta.mail.Transport.send(msg);
    }
}
