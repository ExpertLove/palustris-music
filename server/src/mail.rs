use lettre::message::header::ContentType;
use lettre::transport::smtp::authentication::Credentials;
use lettre::{AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor};

/// Настройки SMTP из окружения. Без MUSIC_SMTP_HOST письма не отправляются.
#[derive(Clone)]
pub struct Mailer {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub pass: String,
    pub from: String,
    pub base_url: String,
}

impl Mailer {
    pub fn from_env() -> Self {
        let env = |k: &str, d: &str| std::env::var(k).unwrap_or_else(|_| d.to_string());
        Mailer {
            host: env("MUSIC_SMTP_HOST", ""),
            port: env("MUSIC_SMTP_PORT", "25").parse().unwrap_or(25),
            user: env("MUSIC_SMTP_USER", ""),
            pass: env("MUSIC_SMTP_PASS", ""),
            from: env("MUSIC_SMTP_FROM", "noreply@palustris.ru"),
            base_url: env("MUSIC_BASE_URL", "https://music.palustris.ru"),
        }
    }

    pub fn enabled(&self) -> bool {
        !self.host.is_empty()
    }

    pub async fn send_verification(&self, to: &str, token: &str) -> anyhow::Result<()> {
        let link = format!("{}/api/verify?token={}", self.base_url.trim_end_matches('/'), token);
        let body = format!(
            "Привет!\n\nПодтверди почту для входа в «Музыку»:\n{link}\n\nСсылка действует 24 часа. \
             Если ты не регистрировался — просто удали это письмо.\n"
        );
        let msg = Message::builder()
            .from(self.from.parse()?)
            .to(to.parse()?)
            .subject("Подтверждение почты — Музыка")
            .header(ContentType::TEXT_PLAIN)
            .body(body)?;

        // С логином — только через STARTTLS (pmail не даёт AUTH без шифрования).
        let transport = if self.user.is_empty() {
            AsyncSmtpTransport::<Tokio1Executor>::builder_dangerous(&self.host).port(self.port).build()
        } else {
            AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(&self.host)?
                .port(self.port)
                .credentials(Credentials::new(self.user.clone(), self.pass.clone()))
                .build()
        };
        transport.send(msg).await?;
        Ok(())
    }
}

/// HTML-страница после перехода по ссылке — без SPA, чтобы работало из письма.
pub fn verify_page(ok: bool) -> String {
    let (title, text, color) = if ok {
        ("Почта подтверждена", "Можно возвращаться в приложение.", "#a78bfa")
    } else {
        ("Ссылка недействительна", "Она устарела или уже использована. Отправь письмо заново в аккаунте.", "#fb7185")
    };
    format!(
        r#"<!doctype html><meta charset="utf-8"><title>{title}</title>
<body style="margin:0;height:100vh;display:grid;place-items:center;background:#07060d;color:#f2efff;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif">
<div style="text-align:center;max-width:420px;padding:32px;border-radius:20px;border:1px solid rgba(255,255,255,.1);background:#12102a">
<div style="font-size:38px;color:{color}">●</div>
<h1 style="margin:12px 0 8px;font-size:22px">{title}</h1>
<p style="margin:0 0 20px;color:#928ba9">{text}</p>
<a href="/" style="display:inline-block;padding:11px 22px;border-radius:999px;color:#fff;text-decoration:none;background:linear-gradient(120deg,#8b5cf6,#2563eb)">Открыть Музыку</a>
</div></body>"#
    )
}