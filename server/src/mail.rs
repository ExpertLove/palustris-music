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
            "Привет!

Подтверди почту для входа в «Музыку»:
{link}

Ссылка действует 24 часа.              Если ты не регистрировался — просто удали это письмо.
"
        );
        self.send(to, "Подтверждение почты — Музыка", body).await
    }

    pub async fn send_reset(&self, to: &str, token: &str) -> anyhow::Result<()> {
        let link = format!("{}/reset?token={}", self.base_url.trim_end_matches('/'), token);
        let body = format!(
            "Привет!

Кто-то (надеемся, ты) попросил сбросить пароль в «Музыке».
             Задать новый пароль:
{link}

Ссылка действует 1 час и сработает один раз.              Если это был не ты — ничего не делай, пароль останется прежним.
"
        );
        self.send(to, "Сброс пароля — Музыка", body).await
    }

    async fn send(&self, to: &str, subject: &str, body: String) -> anyhow::Result<()> {
        let msg = Message::builder()
            .from(self.from.parse()?)
            .to(to.parse()?)
            .subject(subject)
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
        ("Почта подтверждена", "Можно возвращаться к музыке.", "#7ee2a8")
    } else {
        ("Ссылка не сработала", "Она устарела или уже использована. Отправь письмо заново в профиле.", "#ff8a8a")
    };
    format!(
        r#"<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{title}</title>
<body style="margin:0;min-height:100vh;display:grid;place-items:center;background:#0c0d10;color:#f3f1ec;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif">
<main style="text-align:center;max-width:420px;padding:36px 28px">
<div style="width:56px;height:56px;margin:0 auto 18px;border-radius:50%;background:{color};opacity:.9"></div>
<h1 style="margin:0 0 8px;font-size:26px;letter-spacing:-.01em">{title}</h1>
<p style="margin:0 0 26px;color:#a3a3ad">{text}</p>
<a href="/" style="display:inline-block;padding:13px 26px;border-radius:999px;color:#0c0d10;background:#f3f1ec;text-decoration:none;font-weight:600">Открыть Музыку</a>
</main></body></html>"#
    )
}
