package fr.citeenjeux.cej.infrastructure;

import fr.citeenjeux.cej.application.UserInvitations.Issued;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class InvitationMail {
    private final JavaMailSender mail;
    private final String from;
    private final String publicUrl;

    public InvitationMail(JavaMailSender mail, @Value("${cej.mail.from}") String from,
                          @Value("${cej.public-url}") String publicUrl) {
        var uri = java.net.URI.create(publicUrl);
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("PUBLIC_URL doit être une URL HTTP(S) sans query ni fragment");
        this.mail = mail; this.from = from; this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public boolean sendReset(Issued reset) {
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(reset.email());
        message.setSubject("Réinitialiser votre mot de passe · Cité en jeux");
        message.setText("Bonjour,\n\nUn administrateur a demandé la réinitialisation de votre mot de passe.\n"
            + "Choisissez un nouveau mot de passe :\n" + publicUrl + "/reset-password.html#token=" + reset.token()
            + "\n\nCe lien est à usage unique et expire le " + reset.expiresAt().atZone(java.time.ZoneId.of("Europe/Paris"))
            + ".\nVotre mot de passe actuel reste valable tant que vous ne confirmez pas ce changement.\n"
            + "Si cette demande vous surprend, contactez un administrateur.\n");
        try { mail.send(message); return true; }
        catch (MailException exception) { return false; }
    }

    /** Appelé après commit : une panne SMTP conserve le compte et autorise un renvoi admin. */
    public boolean send(Issued invitation) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(invitation.email());
        message.setSubject("Votre invitation à Cité en jeux");
        message.setText("Bonjour,\n\nUn administrateur vous invite à rejoindre Cité en jeux.\nChoisissez votre mot de passe :\n"
            + publicUrl + "/activate.html#token=" + invitation.token()
            + "\n\nCe lien est à usage unique et expire le " + invitation.expiresAt().atZone(java.time.ZoneId.of("Europe/Paris"))
            + ".\nSi le lien a expiré, contactez un administrateur de l’association.\n");
        try { mail.send(message); return true; }
        catch (MailException exception) { return false; }
    }
}
