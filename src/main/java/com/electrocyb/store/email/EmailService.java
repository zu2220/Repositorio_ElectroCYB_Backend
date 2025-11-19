package com.electrocyb.store.email;

import com.electrocyb.store.pedido.Pedido;
import com.electrocyb.store.pedido.OrderItem;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    // =====================================
    //   MÉTODO GENERAL PARA ENVIAR EMAIL HTML
    // =====================================
    public void sendHtmlEmail(EmailRequest request) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();

        MimeMessageHelper helper = new MimeMessageHelper(
                message,
                MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                StandardCharsets.UTF_8.name());

        helper.setTo(request.to());
        helper.setSubject(request.subject());
        helper.setText(request.html(), true);
        helper.setFrom("no-reply@electrocyb.com");

        mailSender.send(message);
    }

    // =====================================
    //   CORREO DE CONFIRMACIÓN DE PEDIDO
    // =====================================
    public void sendOrderConfirmation(Pedido pedido) throws MessagingException {
        if (pedido.getCliente() == null || pedido.getCliente().getEmail() == null) {
            return;
        }

        String html = buildOrderHtml(pedido);

        EmailRequest req = new EmailRequest(
                pedido.getCliente().getEmail(),
                "Confirmación de tu pedido " + pedido.getNumeroPedido(),
                html);

        sendHtmlEmail(req);
    }

    // =====================================
    //   🔥 NUEVO: CORREO DE REGISTRO
    // =====================================
    public void sendRegistrationConfirmation(String email, String nombre)
            throws MessagingException {

        String html = "<h2>¡Bienvenido a ElectroCYB! ⚡</h2>"
                + "<p>Hola <strong>" + safe(nombre) + "</strong>,</p>"
                + "<p>Tu cuenta ha sido creada con éxito.</p>"
                + "<p>Ya puedes iniciar sesión y disfrutar de nuestros productos.</p>"
                + "<br><p>Gracias por confiar en ElectroCYB.</p>";

        EmailRequest req = new EmailRequest(
                email,
                "✔ Registro Exitoso – ElectroCYB",
                html
        );

        sendHtmlEmail(req);
    }

    // =====================================
    //   🔥 NUEVO: CORREO DE INICIO DE SESIÓN
    // =====================================
    public void sendLoginNotification(String email, String nombre)
            throws MessagingException {

        String html = "<h2>Inicio de sesión detectado</h2>"
                + "<p>Hola <strong>" + safe(nombre) + "</strong>,</p>"
                + "<p>Hemos registrado un inicio de sesión en tu cuenta.</p>"
                + "<p>Si fuiste tú, ignora este mensaje.</p>"
                + "<p>Si NO reconoces este acceso, cambia tu contraseña.</p>"
                + "<br><p>Equipo de Seguridad – ElectroCYB ⚡</p>";

        EmailRequest req = new EmailRequest(
                email,
                "🔔 Nuevo inicio de sesión en tu cuenta",
                html
        );

        sendHtmlEmail(req);
    }

    // =====================================
    //   HTML DEL PEDIDO (YA LO TENÍAS)
    // =====================================
    private String buildOrderHtml(Pedido pedido) {

        StringBuilder itemsHtml = new StringBuilder();

        for (OrderItem item : pedido.getItems()) {
            itemsHtml.append("<tr>")
                    .append("<td style='padding:8px;border-bottom:1px solid #eee;'>")
                    .append(item.getNombre())
                    .append("</td>")
                    .append("<td style='padding:8px;border-bottom:1px solid #eee;text-align:center;'>")
                    .append(item.getCantidad())
                    .append("</td>")
                    .append("<td style='padding:8px;border-bottom:1px solid #eee;text-align:right;'>S/ ")
                    .append(item.getPrecio())
                    .append("</td>")
                    .append("</tr>");
        }

        return "<html>"
                + "<body style='font-family:Arial,sans-serif;background:#f5f5f5;padding:20px;'>"
                + "<div style='max-width:600px;margin:auto;background:white;border-radius:10px;border:1px solid #ddd;overflow:hidden;'>"

                + "<div style='background:#4f46e5;color:white;padding:16px 24px;'>"
                + "<h2 style='margin:0;'>ElectroCYB – Confirmación de Pedido</h2>"
                + "</div>"

                + "<div style='padding:24px;'>"
                + "<p>Hola <strong>" + safe(pedido.getCliente().getNombre()) + "</strong>,</p>"
                + "<p>Gracias por tu compra. Hemos recibido tu pedido:</p>"

                + "<h3>Código: " + pedido.getNumeroPedido() + "</h3>"

                + "<table style='width:100%;border-collapse:collapse;margin-top:16px;'>"
                + "<thead>"
                + "<tr>"
                + "<th style='text-align:left;border-bottom:1px solid #ccc;padding:8px;'>Producto</th>"
                + "<th style='text-align:center;border-bottom:1px solid #ccc;padding:8px;'>Cant.</th>"
                + "<th style='text-align:right;border-bottom:1px solid #ccc;padding:8px;'>Precio</th>"
                + "</tr>"
                + "</thead>"
                + "<tbody>"
                + itemsHtml
                + "</tbody>"
                + "</table>"

                + "<p style='margin-top:16px;'>"
                + "<strong>Total:</strong> S/ " + pedido.getTotal()
                + "</p>"

                + "<p>Estado actual: <strong>" + pedido.getEstado() + "</strong></p>"

                + "<p>Gracias por confiar en ElectroCYB ⚡</p>"
                + "</div>"
                + "</div>"
                + "</body>"
                + "</html>";
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}