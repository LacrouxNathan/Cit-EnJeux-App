# App CeJ — Cité en jeux

Application privée de l’association. Règles métier : [agent.md](agent.md).

```text
backend/      Java 21, Spring Boot 3.5, Spring Security, JDBC et Flyway
front end/    Pages HTML/CSS/JavaScript, sans dépendance
compose.yaml  Application complète et PostgreSQL 17
```

Stack Java / Spring Boot / PostgreSQL confirmée le 16 novembre 2026. Le frontend reste indépendant du choix futur d’un framework.

Identité visuelle : logo original de l’association dans `front end/logo_cej.png`, bleu `#5AD0E5` et orange `#F99200`. Les thèmes clair/sombre emploient des variantes adaptées au contraste des textes. Le logo est inclus dans l’image Docker et accessible sur la page de connexion.

## Démarrer avec Docker (recommandé)

Seuls Docker et Docker Compose v2 sont nécessaires : Java, Maven et Node sont exécutés dans les conteneurs.

```bash
cp .env.example .env
```

Éditer `.env` : choisir `DB_PASSWORD`, mettre `CEJ_BOOTSTRAP_ENABLED=true` et renseigner l’email, le mot de passe (6 caractères minimum), le prénom et le nom du premier administrateur. Mettre les valeurs entre apostrophes si elles contiennent des caractères spéciaux, notamment `$` ou `#` ; Compose conserve ainsi leur valeur littérale.

```bash
docker compose up --build -d --wait
```

Ouvrir **http://localhost:8080** (port configurable avec `APP_PORT`). Le frontend est inclus dans le serveur Spring Boot : une seule origine pour les pages et l’API, sans serveur frontend supplémentaire. La base attend d’être prête avant le démarrage de l’application, suivant le [mécanisme Compose de dépendances saines](https://docs.docker.com/compose/how-tos/startup-order/).

Après création du compte, remettre `CEJ_BOOTSTRAP_ENABLED=false`, vider `CEJ_BOOTSTRAP_PASSWORD` dans `.env`, puis appliquer :

```bash
docker compose up -d --wait
```

Les comptes sont conservés dans le volume PostgreSQL. La base n’expose aucun port sur l’hôte. L’application est accessible uniquement sur localhost ; `COOKIE_SECURE=false` est réservé à cet accès HTTP local. Pour HTTPS, utiliser `COOKIE_SECURE=true`. Le profil Spring `local` ne doit pas être utilisé dans le conteneur, car il limite l’écoute à sa propre boucle locale.

Commandes utiles :

```bash
docker compose logs -f app
docker compose ps
docker compose down
docker compose up --build -d --wait
```

`down` conserve les données. `down -v` supprime les volumes et les données : à réserver à une remise à zéro volontaire.

L’image utilise une [construction multi-stage](https://docs.docker.com/build/building/multi-stage/) : vérification et tests frontend avec Node, compilation et tests de sécurité backend avec Maven, puis exécution Java 21 avec un utilisateur non root. Le conteneur applicatif utilise un système de fichiers en lecture seule et un répertoire temporaire en mémoire. `.env`, les secrets locaux et les artefacts de compilation sont exclus du contexte de build. Les tests Testcontainers PostgreSQL restent exécutés séparément avec la commande Maven ci-dessous ; le build ne monte pas le socket Docker.

## Démarrer depuis l’IDE (optionnel)

Prérequis : Java 21, Maven 3.6.3 ou supérieur et PostgreSQL (ou Docker Compose).

Depuis la racine, choisir un secret local puis démarrer la base :

```bash
read -rs -p 'Mot de passe PostgreSQL : ' DB_PASSWORD
echo
export DB_PASSWORD
docker compose -f compose.yaml -f compose.local-db.yaml up -d database
```

Avec PostgreSQL déjà installé, créer une base `cej` et un utilisateur `cej`, ou configurer `DB_URL`, `DB_USER` et `DB_PASSWORD`. Flyway applique les migrations au démarrage.

Lors du **premier démarrage uniquement**, configurer le premier administrateur :

```bash
export CEJ_BOOTSTRAP_ENABLED=true
export CEJ_BOOTSTRAP_EMAIL='admin@votre-association.fr'
export CEJ_BOOTSTRAP_FIRST_NAME='Votre prénom'
export CEJ_BOOTSTRAP_LAST_NAME='Votre nom'
read -rs -p 'Mot de passe administrateur (6 caractères minimum) : ' CEJ_BOOTSTRAP_PASSWORD
echo
export CEJ_BOOTSTRAP_PASSWORD
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

Ouvrir **http://localhost:8080** et utiliser les identifiants choisis. Spring Boot sert le frontend et l’API sur la même origine : ne pas lancer le serveur Python pour tester l’authentification. Le profil `local` écoute uniquement sur `127.0.0.1` et autorise le cookie sur HTTP local.

Après arrêt du serveur, retirer les variables d’amorçage puis redémarrer avec la même commande Maven :

```bash
unset CEJ_BOOTSTRAP_ENABLED CEJ_BOOTSTRAP_EMAIL CEJ_BOOTSTRAP_FIRST_NAME CEJ_BOOTSTRAP_LAST_NAME CEJ_BOOTSTRAP_PASSWORD
```

Le compte reste enregistré. L’amorçage, explicite et transactionnel, ne crée un compte que si la table des utilisateurs est vide ; il ne réinitialise jamais un mot de passe existant. Aucun compte ni secret par défaut. La longueur minimale de 6 caractères s’applique à l’amorçage et à l’activation des comptes.

## Authentification

| Route | Comportement |
| --- | --- |
| `GET /api/auth/csrf` | Jeton CSRF et nom de l’en-tête à envoyer ; crée une session anonyme |
| `POST /api/auth/login` | JSON `email`/`password` avec en-tête CSRF ; réponse `{ "authenticated": true }` si valide |
| `GET /api/auth/me` | Informations du compte connecté uniquement ; sinon `401` |
| `POST /api/auth/logout` | Jeton CSRF requis ; invalide la session et le cookie, réponse `204` |

Email normalisé (espaces périphériques et casse), mot de passe conservé exactement. Les mots de passe sont stockés sous forme de hachages PBKDF2 salés avec les paramètres Spring Security 5.8, jamais en clair. Cookie de session `HttpOnly`, `SameSite=Lax`, `Secure` par défaut et expiration après 30 minutes d’inactivité. La session change d’identifiant à la connexion, et le jeton CSRF est renouvelé. Voir les mécanismes officiels [de persistance de session](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html) et [de protection CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

Un mauvais mot de passe, un compte inconnu ou désactivé produit la même erreur `401`. L’adhésion n’est pas nécessaire pour se connecter. Le serveur recharge le statut et les droits à chaque requête : désactiver le compte invalide une session ouverte. L’API ne retourne ni hachage, ni identité d’autres utilisateurs. Les routes privées exigent une authentification et `/api/admin/**` un administrateur.

Sans session valide, ouvrir une page privée redirige vers `/`, la page de connexion. Les API et ressources privées continuent de répondre `401` en JSON ; les accès refusés à un membre connecté restent en `403`. La page d’activation reçue par invitation reste accessible sans connexion.

Limitation configurable : `AUTH_MAX_ATTEMPTS` (10 par défaut) sur `AUTH_WINDOW_SECONDS` (900 par défaut), par adresse réseau directe. Il s’agit d’une limite de requêtes en mémoire, sans suspension du compte ; elle couvre aussi les connexions réussies. Pour plusieurs instances ou un reverse proxy, prévoir une limite partagée au niveau de l’infrastructure ; les en-têtes d’adresse fournis par le client ne sont pas considérés comme fiables.

La page `/member.html` confirme la session et permet la déconnexion. Les administrateurs y disposent du lien **Gérer les utilisateurs**. Le tableau de bord métier et la récupération de mot de passe restent à développer ; aucun endpoint public d’inscription n’est ajouté.

## Créer un utilisateur

1. Se connecter avec un compte administrateur, puis ouvrir **Gérer les utilisateurs**.
2. Saisir prénom, nom, email, pseudonyme facultatif et statut d’adhésion.
3. Cliquer sur **Créer et envoyer l’invitation**. Le compte est créé avec les droits membre, sans mot de passe et sans accès tant que l’invitation n’est pas acceptée.
4. Le destinataire ouvre son invitation et choisit un mot de passe (6 caractères minimum).

En Docker local, les emails sont collectés par [Mailpit](https://mailpit.axllent.org/docs/install/docker/) : ouvrir **http://localhost:8025** pour lire l’invitation. Aucun email ne part vers une boîte réelle avec la configuration par défaut. Les messages Mailpit sont temporaires. La base des comptes reste dans le volume PostgreSQL.

Le lien expire au bout de `INVITATION_HOURS` (48 heures par défaut, valeur technique configurable), n’est utilisable qu’une fois et n’est stocké en base que sous forme d’empreinte SHA-256. Le secret se trouve dans le fragment du lien email, est envoyé uniquement dans le corps du POST d’activation et retiré immédiatement de l’adresse affichée. Rafraîchir la page d’activation nécessite donc de rouvrir le lien original.

Les administrateurs peuvent renvoyer une invitation non acceptée, y compris expirée. Un renvoi invalide le lien précédent. Un compte désactivé ou déjà activé ne peut pas recevoir cette invitation initiale. Les doublons d’email sont rejetés sans distinction de casse. Compte, invitation et audit sont écrits dans la même transaction. L’envoi SMTP suit le commit : si l’envoi échoue, le formulaire indique que le compte est créé et propose d’utiliser le renvoi ; aucun envoi automatique n’est effectué.

Pour envoyer réellement les invitations, configurer `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` et `MAIL_FROM` dans `.env`, puis recréer l’application avec `docker compose up -d --wait`. `PUBLIC_URL` doit être l’adresse accessible par les destinataires (avec `APP_PORT` si différent de 8080), HTTPS en production. L’envoi utilise [Spring Mail avec délais réseau bornés](https://docs.spring.io/spring-boot/reference/io/email.html). Le service Mailpit sert uniquement au développement ; ne pas exposer son interface sur Internet.

| Route | Accès |
| --- | --- |
| `GET /api/admin/users?page=0` | Admin, liste paginée de 20 comptes sans secrets |
| `POST /api/admin/users` | Admin et CSRF, création et invitation |
| `POST /api/admin/users/{id}/invitation` | Admin et CSRF, renvoi avec rotation du lien |
| `POST /api/invitations/activate` | Lien secret valide et CSRF, choix du mot de passe |

La réponse d’invitation contient uniquement l’ID, l’échéance et `deliveryStatus` (`SENT` ou `FAILED`), jamais le lien secret. Les créations, invitations et activations sont historisées dans `account_audit`, sans mot de passe ni jeton. La migration V2 préserve les comptes et mots de passe existants.

## Tests et compilation

```bash
mvn -f backend/pom.xml verify
node --check 'front end/login.js'
node --check 'front end/member.js'
node 'front end/tests/login.test.cjs'
node 'front end/tests/activate.test.cjs'
```

Les tests Spring vérifient l’authentification, les refus visiteur/membre, la confidentialité de la réponse, les comptes désactivés, la révocation de session, la fixation de session, CSRF et la déconnexion. Le test unitaire de limitation utilise une horloge contrôlée. Les tests PostgreSQL utilisent Testcontainers : **ignorés si Docker est indisponible** ; un résultat Maven vert avec un test ignoré ne valide pas la migration PostgreSQL.

Les fichiers du frontend sont intégrés au JAR lors du build. Après une modification du frontend, relancer Maven pour les recopier.

```bash
mvn -f backend/pom.xml package
java -jar backend/target/cej-backend-0.1.0-SNAPSHOT.jar
```

En production : configurer la base par variables d’environnement, fournir HTTPS, conserver le cookie `Secure` et ne pas activer le profil `local`. Les sessions sont en mémoire et perdues au redémarrage. Aucun secret ne doit être commité ; `.env` est ignoré.

## Ludothèque et fiches de jeux

Depuis **Mon espace → Parcourir la ludothèque**, les comptes actifs consultent un catalogue privé. Les administrateurs disposent de **Ajouter un jeu**, puis des actions de gestion dans chaque fiche.

- Fiches : nom, type (jeu de base, extension, extension autonome), rattachement au jeu de base, éditeur, auteur, année, intervalle de joueurs, durée, âge, description, catégories et mécaniques. Seul le nom et le type sont obligatoires pour un jeu de base ; une extension nécessite son rattachement.
- Avertissement sur les noms ressemblants, y compris les fiches archivées ; l’enregistrement reste autorisé après confirmation. Les extensions sont regroupées dans la fiche du jeu de base et recherchables par leur propre nom.
- Catalogue : recherche par nom, filtres joueurs/disponibilité, tris alphabétique/date/disponibilité, vues grille et liste, pagination serveur de **20 jeux**. Les filtres sont repliables sur mobile. Le mode d’affichage est mémorisé si le navigateur le permet.
- Couvertures PNG/JPEG : téléversement administratif, 5 Mo et 16 millions de pixels maximum (limites techniques), validation du contenu réel. Stockage dans PostgreSQL, lecture authentifiée, versions précédentes consultables uniquement depuis l’historique administratif.
- Emplacements : création à la racine ou sous un autre emplacement. Les **doublons de noms sont autorisés**, conformément à l’arbitrage du 16 novembre 2026. Les menus distinguent les chemins identiques par un suffixe d’identifiant. Un emplacement contenant directement des exemplaires ne peut pas recevoir d’enfant.
- Exemplaires : création de plusieurs lignes en une transaction, identifiant généré automatiquement côté serveur et masqué dans l’interface et les réponses de consultation, emplacement feuille obligatoire, état physique, pièces manquantes en texte libre, commentaire administratif, blocage avec motif. Toute modification ultérieure est individuelle. Les identifiants existants et leurs réservations sont conservés, sans renumérotation. Ils ne sont plus modifiables et ne sont pas exposés dans l’affichage des historiques.
- Archivage/restauration individuels des exemplaires, sans suppression. Après restauration, l’exemplaire reste bloqué : l’administrateur doit revoir son état et son autorisation de prêt.
- Archivage d’une fiche avec motif : tous ses exemplaires et ceux de ses extensions doivent être archivés auparavant. L’archivage du jeu de base archive ses extensions après confirmation, sans archiver de copies automatiquement. La restauration d’une fiche ne restaure ni extensions ni copies ; une extension non autonome exige un parent actif.
- Historiques administratifs : acteur, date, champs et valeurs avant/après, regroupés par sauvegarde. L’audit fait partie de la transaction métier. Les sauvegardes concurrentes utilisent une version ; les écritures de catalogue utilisent également un verrou transactionnel PostgreSQL pour protéger les opérations de stock et les cascades.

**Visibilité :** une fiche archivée ou sans exemplaire actif est invisible aux membres, y compris par son URL et sa couverture. Un jeu avec uniquement des exemplaires bloqués reste visible. Les membres ne reçoivent ni commentaires administratifs, ni historiques, ni informations d’emprunteurs. Les jeux et exemplaires ne sont jamais supprimés physiquement par l’API.

Cette livraison couvre les fiches et le socle de stock qui leur est nécessaire. Le parcours d’emprunt doit désormais être défini sans saisie d’identifiant sur la boîte (arbitrage du 16 novembre 2026). Les emprunts/retours et leurs dépendances d’extensions ne sont pas encore implémentés : la disponibilité actuelle indique uniquement les copies actives non bloquées. La modification, le déplacement et l’archivage de l’arbre des emplacements restent à développer ; sa création hiérarchique et les déplacements individuels de copies sont disponibles. Aucun import de stock, export, scan ou réservation n’est ajouté. Le préremplissage d’une fiche à partir d’un lien BGG est décrit ci-dessous.

Routes principales :

| Route | Accès |
| --- | --- |
| `GET /api/library/games` et `/api/library/games/{id}` | Compte actif, visibilité contrôlée côté serveur |
| `POST /api/admin/library/games`, `PUT /api/admin/library/games/{id}` | Admin + CSRF |
| `POST /api/admin/library/games/{id}/copies` | Admin + CSRF, création atomique |
| `PUT /api/admin/library/copies/{id}` | Admin + CSRF, modification individuelle |
| `POST /api/admin/library/{games ou copies}/{id}/{archive ou restore}` | Admin + CSRF, motif et version requis |
| `GET /api/library/games/{id}/cover` | Même visibilité que la fiche |
| `PUT/DELETE /api/admin/library/games/{id}/cover?version=…` | Admin + CSRF |
| `GET /api/admin/library/{game ou copy}/{id}/history` | Admin uniquement |

Tests complémentaires : `node 'front end/tests/library.test.cjs'`. La suite `LibraryIntegrationTest` vérifie les invariants sur PostgreSQL réel avec Testcontainers. Le build Docker vérifie aussi la syntaxe des modules et les tests frontend. La migration V3 ajoute la ludothèque sans modifier les comptes existants.


## Préremplir une fiche depuis un lien BoardGameGeek

Dans **Ajouter un jeu**, coller le lien HTTPS de la fiche BGG puis cliquer sur **Récupérer les informations**. Il n’y a pas de recherche BGG : seuls les liens `https://boardgamegeek.com/boardgame/…` ou `/boardgameexpansion/…` (avec ou sans `www`) sont acceptés. Le numéro du jeu sert à appeler l’API officielle ; la page web n’est pas téléchargée.

Le formulaire propose nom, éditeurs, auteurs, année, joueurs, durée, âge, description, catégories, mécaniques et couverture lorsqu’ils sont disponibles. Les champs restent modifiables avant sauvegarde. Vérifier l’édition et le type d’extension : l’autonomie et le rattachement au jeu de base local ne sont pas décidés automatiquement. Les données peuvent être en anglais ; aucune traduction automatique n’est effectuée. Aucun jeu ni exemplaire n’est créé au clic d’import : seul **Enregistrer la fiche** crée la fiche, sans exemplaire.

Configuration nécessaire : enregistrer l’application auprès de [BGG](https://boardgamegeek.com/applications), attendre son approbation puis renseigner son jeton dans `.env` :

```dotenv
BGG_API_TOKEN='votre-jeton-application-bgg'
```

Appliquer avec `docker compose up -d --wait`. Ne jamais coller le jeton dans le frontend ou le commiter. Consulter le [guide officiel d’accès](https://boardgamegeek.com/using_the_xml_api) et les conditions de l’autorisation obtenue auprès de BGG. Sans jeton, la saisie manuelle et le reste de l’application continuent de fonctionner ; seul le préremplissage affiche une erreur de configuration.

L’API locale `POST /api/admin/library/bgg/preview` exige un administrateur ou membre du CA actif et un jeton CSRF. Les appels sortants vont uniquement à `boardgamegeek.com/xmlapi2/thing` ; les redirections sont désactivées. Le XML est limité à 2 Mo, sans DTD ni entités externes, les délais réseau sont bornés et une nouvelle récupération BGG est autorisée toutes les cinq secondes au maximum par instance. Les couvertures sont téléchargées uniquement depuis les CDN BGG autorisés, sans jeton d’accès, et passent les mêmes contrôles PNG/JPEG, taille et pixels que les couvertures manuelles. Le navigateur prévisualise une image locale temporaire, sans appel direct à BGG.

Le choix d’importer la couverture est proposé après récupération. La fiche et sa référence BGG sont enregistrées d’abord ; si l’enregistrement de la couverture échoue, un message précise que la fiche existe et qu’une couverture peut être téléversée manuellement. Les versions de couverture et l’historique sont conservés selon les règles habituelles. La migration V4 ajoute la référence BGG aux fiches, sans modifier les jeux existants ni leur stock.

L’identifiant et le lien source BGG sont conservés ; les fiches concernées affichent **Source : BoardGameGeek** et le logo **Powered by BGG**, avec un lien vers BGG. Le logo local `front end/powered-by-bgg.png` est l’asset d’attribution BGG (copie distribuée par hoobby.net), et non une création propre à l’association.

Les tests BGG utilisent des réponses XML et images simulées, ainsi qu’un serveur HTTP local pour le transport. Ils vérifient le filtrage des liens, le jeton côté serveur, l’absence de redirections, les limites, les erreurs BGG et les protections XML. Ils ne constituent pas une validation d’accès à BGG avec une application approuvée : cet accès nécessite votre jeton réel.


## Gestion des membres et des rôles

Dans **Mon espace → Gérer les utilisateurs**, les administrateurs disposent désormais de la recherche par nom/pseudonyme/email, des filtres rôle/statut/adhésion, de la modification des comptes et de leur historique.

**Modifier** permet de changer prénom, nom, pseudonyme, email, statut d’adhésion, rôle (**Membre**, **Membre du CA** ou **Administrateur**) et état actif/désactivé. Les administrateurs ont les mêmes droits : aucun rôle de super-administrateur n’est ajouté. Les comptes invités sont toujours créés comme membres ; un administrateur peut ensuite leur attribuer les droits administrateur. Cela ne remplace pas l’activation par invitation.

- Un administrateur actif ayant activé son compte doit toujours rester disponible. La désactivation ou le retrait du rôle du dernier administrateur est refusé, même si deux opérations sont effectuées simultanément ou si un autre administrateur est encore en attente d’activation.
- Les changements de rôle prennent effet sur les sessions déjà ouvertes, dès leur prochaine requête. Un administrateur peut retirer ses propres droits si un autre administrateur actif ayant activé son compte existe ; il revient alors à son espace membre.
- Désactiver un compte interdit ses connexions et invalide ses sessions. Réactiver le compte impose une nouvelle connexion et ne rend jamais les anciennes sessions valides. Les comptes et tous leurs historiques sont conservés, sans suppression physique.
- Modifier l’email invalide aussi les sessions du compte. Le mot de passe et l’UUID restent inchangés. Pour une invitation non acceptée, les anciens liens sont invalidés et un administrateur doit explicitement renvoyer une invitation à la nouvelle adresse. Une désactivation invalide également les invitations en attente.
- Changer le statut d’adhésion ne désactive pas le compte et ne ferme pas sa session : l’adhésion est indépendante de l’accès au catalogue et aux soirées.
- Les modifications sensibles demandent une confirmation dans l’interface. Le serveur vérifie les droits indépendamment de l’interface, contrôle la version de sauvegarde, protège les opérations concurrentes par verrou transactionnel PostgreSQL et écrit l’historique dans la même transaction.

La désactivation n’efface ni ne clôture de prêts. Les modules d’emprunts et de soirées ne sont pas encore implémentés ; l’annulation transactionnelle des inscriptions/tables futures devra être intégrée lors de leur développement, conformément à `agent.md`. La réactivation ne doit pas les recréer.

Les routes `GET/PUT /api/admin/users/{id}` et `GET /api/admin/users/{id}/history` sont réservées aux administrateurs ; le PUT exige aussi CSRF. La liste `GET /api/admin/users` accepte `search`, `status=all|active|inactive|pending`, `role=all|admin|board|member`, `membership=all|current|expired` et `page`. Elle conserve son format tableau et sa pagination de 20 comptes. Aucune API de suppression de compte n’est ajoutée.

La migration V5 conserve les comptes existants, ajoute les versions de sauvegarde/révocation des sessions et les valeurs avant/après à l’audit des comptes. Les mots de passe, jetons d’invitation et versions internes de sécurité ne sont pas exposés dans les DTO administratifs ou l’historique. Les actions d’invitation et d’activation restent consultables avec les modifications de compte. La suite PostgreSQL `MemberManagementTest` vérifie permissions, CSRF, doublons d’email, concurrence du dernier administrateur, révocation des sessions et invitations à une ancienne adresse.


## Rôle Membre du CA

Dans **Gérer les utilisateurs → Modifier → Rôle**, un administrateur peut attribuer **Membre du CA**. Ce rôle permet de créer, modifier, archiver et restaurer les fiches de jeux, leurs couvertures et de préremplir une fiche avec un lien BGG. Les fiches sans exemplaire actif et les fiches archivées lui sont accessibles pour cette gestion. Les membres ordinaires conservent leur accès en lecture au catalogue des jeux actifs possédant un exemplaire actif.

La suppression d’un jeu reste un **archivage avec motif**, avec conservation de son historique et des règles de cascade existantes. Un jeu possédant des exemplaires actifs ne peut pas être archivé : un administrateur doit d’abord régulariser et archiver individuellement les exemplaires concernés. Le CA ne peut pas gérer les exemplaires, les emplacements, les membres, les rôles, les commentaires administratifs ni consulter les historiques administratifs ou les exemplaires archivés.

Les API de fiches conservent leurs routes `/api/admin/library/…` pour compatibilité, mais les permissions sont accordées précisément par méthode et route, et revérifiées dans les services. Aucune permission générale sur `/api/admin/**` n’est accordée au CA. Les DTO du CA contiennent les métadonnées nécessaires à l’édition des jeux et seulement les informations publiques des exemplaires actifs, sans commentaires, versions de copies, comptes ni totaux d’exemplaires archivés.

Les droits prennent effet sur les sessions existantes dès la requête suivante. Un membre du CA ne remplace pas un administrateur dans la protection du dernier administrateur actif. La migration V6 conserve les rôles existants et impose en base l’exclusivité des rôles CA/administrateur. Le champ `boardMember` complète les DTO de gestion et le PUT des comptes ; `administrator` et `boardMember` ne peuvent pas être vrais ensemble. Les anciens clients qui n’envoient pas `boardMember` conservent ce rôle lors d’une modification d’identité.

## Réinitialisation des mots de passe

Dans **Gérer les utilisateurs**, le bouton **Réinitialiser le mot de passe** envoie un lien à l’adresse actuelle d’un membre actif ayant activé son compte. Les comptes invités utilisent toujours le renvoi d’invitation. Le lien expire après une heure, est à usage unique et chaque nouvel envoi invalide le précédent. En cas d’échec SMTP, un message permet de réessayer après correction du service email.

Le membre choisit un mot de passe d’au moins 6 caractères. La confirmation invalide les anciennes sessions, sans changer les rôles ou l’adhésion. Une modification d’email ou une désactivation invalide également les liens en cours. Demande et confirmation sont historisées sans enregistrer le mot de passe ni le jeton en clair. Cette fonction utilise la configuration SMTP et `PUBLIC_URL` existantes ; en local, les emails sont consultables dans Mailpit.
