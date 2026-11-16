# agent.md — App CeJ (Cité en jeux)

> Instructions permanentes pour tout agent IA contribuant au projet App CeJ.
> Document de cadrage au 16 novembre 2026. Langue de travail : français.
> Les règles marquées **VALIDÉ** proviennent des arbitrages fonctionnels du projet. Les éléments **PROPOSÉ** constituent des recommandations et ne doivent pas être traités comme des décisions.

## 1. Mission de l'agent

Construire et maintenir **App CeJ**, application web responsive installable en **PWA** pour l'association de jeux de société **Cité en jeux**. L'application couvre :

1. Un catalogue privé et la gestion du stock physique de la ludothèque.
2. Les emprunts, prolongations administratives et retours autonomes des exemplaires.
3. Les événements/soirées de jeux et les inscriptions aux tables.
4. Des campagnes d'inventaire physique avec relevés et résolution administrative des anomalies.
5. L'administration des comptes, droits et paramètres.

L'agent doit livrer du code fonctionnel, testé et documenté, sans étendre implicitement le périmètre.

### Principes non négociables

- **Le métier prime sur la technique.** Ne pas remplacer une règle validée par une « bonne pratique » supposée.
- **Ne pas inventer d'exigence.** Lorsqu'une décision manque, formuler une hypothèse explicite et demander un arbitrage avant d'implémenter une solution difficile à inverser.
- **Sécurité côté serveur** : toute restriction de visibilité/action doit être contrôlée dans l'API, et pas seulement dans l'interface.
- **Traçabilité** : ne jamais effacer les historiques métier requis.
- **Transactions** : les opérations multi-entités doivent être atomiques si une réalisation partielle rendrait l'état incohérent.
- **Identité stable** : préférer des identifiants techniques immuables distincts des identifiants métiers susceptibles d'évoluer.
- **MVP sobre** : ne pas ajouter de fonctionnalités expressément écartées.

## 2. Stack et architecture

**PROPOSÉ — à confirmer avant figer le dépôt :**

- Frontend : **React + TypeScript**, interface responsive et PWA.
- Backend : **Java + Spring Boot**, API REST et contrôles avec Spring Security.
- Base relationnelle : **PostgreSQL**.
- Stockage des images de couverture : espace géré par l'application, accès authentifié.
- Tests : unitaires, intégration (notamment PostgreSQL), API, et scénarios E2E critiques.
- Migrations SQL versionnées (par exemple Flyway).

Architecture recommandée : séparation claire `domain / application / infrastructure / api`, DTO distincts des entités persistées, contrôle des permissions et des invariants dans les services métier. Ne pas introduire microservices, bus d'événements, CQRS ou complexité non justifiée.

**VALIDÉ :** PWA utilisable sur mobile et ordinateur ; pas d'obligation de publication sur les stores. Thèmes clair/sombre, préférence système par défaut, choix utilisateur mémorisé. Navigation adaptée au terminal (barre latérale desktop, navigation mobile). Tableau de bord montrant les emprunts actifs, tables à venir et informations utiles.

## 3. Identité, comptes et permissions — VALIDÉ

- Connexion par email/mot de passe. **VALIDÉ : longueur minimale du mot de passe de 6 caractères**, y compris pour l’amorçage administrateur. Les comptes sont créés/invités **uniquement par les administrateurs** ; pas d'auto-inscription publique.
- **Réinitialisation du mot de passe :** demandée uniquement par un administrateur depuis la fiche d’un membre actif déjà activé. Lien email à usage unique valable une heure ; un renvoi invalide le précédent. Le mot de passe reste inchangé jusqu’à confirmation ; le changement invalide les sessions existantes et conserve une trace d’audit.
- Activation initiale via invitation email à usage unique et durée de vie limitée ; **seuls les administrateurs** renvoient les invitations expirées.
- Profil : prénom et nom obligatoires, pseudonyme facultatif. Afficher le pseudonyme aux autres utilisateurs s'il existe, sinon le nom ; les administrateurs peuvent consulter l'identité réelle.
- Statut d'adhésion mis à jour manuellement par les administrateurs.
- Seul un compte **actif et à jour d'adhésion** peut commencer un emprunt. Les comptes actifs peuvent participer aux soirées indépendamment de leur adhésion.
- **Rôle Membre du CA — VALIDÉ le 16 novembre 2026 :** peut créer, modifier, archiver et restaurer les fiches de jeux, gérer leurs couvertures et utiliser le préremplissage BGG. Il ne peut pas gérer les comptes, les rôles, les exemplaires ni les emplacements, et n’accède pas aux historiques administratifs. Seuls les administrateurs attribuent ou retirent ce rôle. La suppression d’un jeu reste un archivage, jamais une suppression physique.
- Plusieurs administrateurs, droits équivalents, avec capacité de déléguer/retirer les droits administrateur ; empêcher la suppression du dernier administrateur actif.
- Les administrateurs peuvent désactiver/réactiver les comptes sans effacer leurs historiques. Un compte désactivé ne peut pas se connecter.
- La désactivation n'efface ni ne clôt les emprunts en cours ; un administrateur peut enregistrer leurs retours.
- Désactiver un compte annule ses inscriptions aux **tables futures** et les tables futures qu'il organise, en conservant les traces historiques ; la réactivation ne les recrée pas.
- Ne pas confondre désactivation et demande de suppression de données personnelles (RGPD), à traiter séparément.
- Sessions/cookies sécurisés recommandés ; protections CSRF selon stratégie d'authentification, limitation des tentatives, hachage fort des mots de passe, gestion sûre des secrets.

### Matrice de droits simplifiée

| Action | Visiteur | Membre actif | Membre du CA actif | Administrateur actif |
|---|---:|---:|---:|---:|
| Consulter le catalogue et les exemplaires actifs | Non | Oui | Oui | Oui |
| Consulter les soirées et inscriptions | Non | Oui | Oui | Oui |
| Emprunter | Non | Adhésion à jour | Adhésion à jour | Adhésion à jour |
| Retourner ses emprunts | Non | Oui | Oui | Oui |
| Organiser/rejoindre une table | Non | Oui | Oui | Oui |
| Créer/modifier/archiver/restaurer les fiches de jeux et couvertures | Non | Non | Oui | Oui |
| Consulter les fiches sans exemplaire actif ou archivées | Non | Non | Oui | Oui |
| Modifier exemplaires, emplacements, comptes et rôles | Non | Non | Non | Oui |
| Lire les historiques administratifs et exemplaires archivés | Non | Non | Non | Oui |
| Voir l’identité des autres emprunteurs | Non | Non | Non | Oui |

Les vérificateurs d'une campagne d'inventaire disposent d'autorisations **temporaires et limitées à cette campagne** (voir §7), pas d'un rôle global d'administration.

## 4. Jeux, exemplaires et catalogue — VALIDÉ

### Modèle de données

- `Game` : fiche de jeu (nom obligatoire ; champs facultatifs comme éditeur, auteur, année, joueurs min/max, durée, âge, description, catégories/mécaniques, couverture).
- `GameCopy` : exemplaire physique lié à exactement un jeu, avec identifiant technique immuable et **identifiant d'inventaire métier unique**.
- Un jeu peut avoir zéro, un ou plusieurs exemplaires. L'association seule possède les exemplaires de la ludothèque : pas de dépôt de jeux personnels dans son stock.
- Types distincts : `BASE_GAME`, `EXPANSION`, `STANDALONE_EXPANSION`. Chaque extension est une fiche propre, rattachée à un jeu de base, et peut posséder ses propres exemplaires.
- Une extension **non autonome** nécessite un jeu de base compatible pour l'emprunt ; une extension autonome peut être empruntée seule.
- **VALIDÉ (16 novembre 2026, nouvel arbitrage) :** l’identifiant d’inventaire est généré automatiquement côté serveur et caché dans toute l’interface. Il ne figure pas dans les formulaires ni les DTO d’affichage. Les identifiants existants sont conservés, sans renumérotation.
- L’identifiant d’inventaire n’est plus modifiable depuis l’application. Les UUID techniques restent stables ; les identifiants historiques et leurs réservations sont conservés à vie, même après archivage. Les historiques stockés sont préservés, mais ces identifiants sont masqués dans leur affichage.
- Les exemplaires portent un état physique, un texte libre facultatif décrivant les pièces manquantes, des commentaires et un blocage explicite des emprunts accompagné de son motif. Les pièces manquantes **ne sont pas** une nomenclature détaillée ni une liste structurée.
- Aucun ajout de photo aux anomalies ou à l'état d'un exemplaire. **Seule la couverture de fiche de jeu** peut comporter une image téléversée.
- Aucune génération/impression d'étiquettes, aucun QR code ou scan intégré au MVP.
- Création **manuelle** des fiches et copies ; préremplissage depuis un lien BGG autorisé après vérification, sans import de stock CSV/Excel. Un administrateur peut créer **plusieurs copies du même jeu en un formulaire**, avec génération automatique de chaque identifiant côté serveur ; création atomique.
- Toutes les **modifications ultérieures de copies** sont individuelles : pas de modification administrative groupée (emplacement, état, blocage, archivage, etc.).
- Pas de fusion de fiches de jeux ni de réaffectation de copies entre fiches, même pour les doublons.
- Avertir lors de la création ou du renommage d'un jeu si le nom ressemble à une fiche existante, y compris archivée ; la création demeure autorisée.

### Préremplissage BGG — VALIDÉ le 16 novembre 2026

- L’administrateur ou le membre du CA colle directement le lien HTTPS d’une fiche BoardGameGeek ; aucune recherche BGG n’est proposée.
- Le backend utilise l’API officielle et un jeton d’application BGG configuré côté serveur. Le jeton ne doit jamais être transmis au navigateur.
- Les informations disponibles et la couverture sont proposées dans le formulaire ; l’administrateur ou le membre du CA vérifie, corrige et confirme avant création. Aucun exemplaire ni stock n’est créé par ce préremplissage.
- Vérifier le type et le rattachement des extensions localement, sans déduire automatiquement leur autonomie.
- Conserver l’identifiant BGG et afficher la source et l’attribution BoardGameGeek sur la fiche. La saisie manuelle reste disponible en cas d’erreur ou d’absence de configuration.

### Visibilité et navigation

- Catalogue et disponibilité accessibles **uniquement aux comptes connectés actifs** ; aucune page de catalogue publique.
- Afficher aux membres seulement les exemplaires **actifs** ; les exemplaires archivés sont visibles uniquement par les administrateurs.
- Masquer automatiquement aux membres toute fiche archivée et tout jeu **sans aucun exemplaire actif** ; conserver ces jeux pour les administrateurs et les membres du CA habilités à gérer les fiches. Réafficher un jeu actif dès qu'il possède au moins un exemplaire actif.
- Un jeu avec exemplaires actifs mais tous empruntés/bloqués reste visible.
- Afficher aux membres le statut de disponibilité et, pour un exemplaire emprunté, **sa date de retour prévue**, mais **jamais l'identité/pseudonyme de l'emprunteur** ni ses historiques.
- Les membres consultent l'état actuel et les pièces manquantes ; l'historique détaillé des copies est strictement réservé aux administrateurs.
- Extensions regroupées sous le jeu de base ; recherche par nom d'extension possible, avec lien explicite vers son jeu de base.
- Recherche du catalogue par **nom** ; filtres **nombre de joueurs** et **disponibilité** seulement dans le MVP. Filtre nombre de joueurs = nombre dans l'intervalle min/max ; jeu sans information de joueurs non compatible avec un filtre numérique ciblé.
- Tris : alphabétique par défaut, date d'ajout (récent d'abord) et disponibilité.
- Modes d'affichage sélectionnables : grille responsive de cartes et liste compacte ; mêmes recherches/filtres/tris.
- Pagination **côté serveur** ; nombre limité de jeux par page. **20 jeux par page est VALIDÉ** (arbitrage du 16 novembre 2026).
- La préférence de mode d'affichage mémorisée est recommandée, non formellement arbitrée.

### Historiques des fiches et copies

- Historique détaillé, réservé aux administrateurs, des **modifications de chaque fiche de jeu** : acteur, date/heure, champ, valeur avant/après. Regrouper les changements d'une même sauvegarde en un événement logique.
- Historique détaillé des modifications des exemplaires : conditions, pièces manquantes, emplacement, identifiant d'inventaire, blocage, archivage/restauration et auteurs.
- Journaux d'audit non modifiables par les utilisateurs, conservés indépendamment des affichages courants ; cohérence transactionnelle avec la modification métier.

### Archivage des jeux et exemplaires

- Aucune suppression physique définitive des fiches de jeux ou exemplaires historiques.
- Archivage d'exemplaire avec motif obligatoire ; possibilité de restauration, identité et historique préservés, état et autorisation d'emprunt à revoir, sans réactivation automatique du droit d'emprunter.
- Une copie empruntée ne peut être archivée tant que l'emprunt n'a pas été régularisé.
- Les fiches de jeux sont archivables/restaurables par les administrateurs et les membres du CA avec motif ; archivage impossible si des copies liées sont encore actives/empruntées et non régularisées.
- **Archiver un jeu de base archive en cascade toutes ses fiches d'extensions**, après confirmation et prévalidation de l'ensemble. Ne pas archiver automatiquement leurs exemplaires physiques : respecter les conditions préalables sur les copies.
- **Restaurer un jeu de base ne restaure aucune extension** : restauration individuelle, seulement lorsque le rattachement est compatible et le jeu parent actif pour une extension non autonome.
- Archivage groupé des **fiches de jeux liées** est une opération métier particulière ; il ne remet pas en cause l'interdiction des modifications groupées de copies.

## 5. Emplacements physiques — VALIDÉ

- Hiérarchie de profondeur variable (salle → armoire → étagère, ou autre), avec identifiant technique stable, `parentId` et libellé.
- **Chaque exemplaire est affecté obligatoirement à une feuille active** de l'arbre, c'est-à-dire un emplacement **sans enfants**. Une armoire sans étagères peut être une feuille valide.
- Plusieurs exemplaires peuvent occuper une même feuille.
- L'emplacement de référence de la copie est conservé lorsqu'elle est empruntée.
- Modifier l'emplacement d'une copie est individuel et réservé aux administrateurs ; journaliser le mouvement.
- Les administrateurs peuvent **renommer librement** les emplacements, y compris occupés, et les déplacer avec **toute leur branche** en conservant IDs et historiques ; journaliser ancien/nouveau nom et ancien/nouveau chemin.
- Empêcher cycles (déplacement sous soi-même ou son descendant), mouvement sous parent archivé et conversion en conteneur d'une feuille contenant directement des copies.
- Les emplacements ne sont jamais supprimés définitivement ; ils peuvent être archivés/restaurés avec traçabilité.
- **Archiver un parent** archive **tous ses descendants** après confirmation, en une seule transaction, **seulement si aucune copie n'est affectée dans la branche**, y compris une copie actuellement empruntée mais dont c'est l'emplacement de référence.
- **Restaurer un parent** ne restaure **aucun descendant**. Restaurer chaque enfant individuellement, après restauration de ses ancêtres.
- Une feuille archivée ne peut pas recevoir de copies. Après restauration du parent, ne pas considérer celui-ci comme feuille s'il possède encore des enfants archivés : l'existence d'enfants compte indépendamment de leur statut.
- **VALIDÉ (16 novembre 2026) :** les doublons de noms d’emplacements sont autorisés, y compris sous un même parent. Distinguer les emplacements par leur identifiant technique.

## 6. Emprunts et retours — VALIDÉ

### Emprunter

- Seul un utilisateur connecté, compte actif, **adhésion à jour**, peut créer un emprunt.
- **Nouvel arbitrage du 16 novembre 2026 :** la saisie d’un identifiant d’inventaire sur la boîte est abandonnée, puisque cet identifiant est désormais automatique et masqué. Le parcours de sélection des exemplaires lors d’un emprunt reste **à arbitrer avant son implémentation**.
- Le membre ajoute **plusieurs exemplaires à un panier** et confirme **en une seule transaction**. Empêcher les doublons ; vérifier toutes les copies, leurs disponibilités, blocages, statuts et les dépendances des extensions juste avant commit.
- Autoriser l'ajout d'extensions avant le jeu de base dans le panier si l'ensemble final est cohérent (ou si une base compatible est déjà empruntée).
- Pas de réservations d'exemplaires, liste d'attente ni files d'attente.
- Durée d'emprunt **configurable par les administrateurs**, avec date d'échéance effectivement enregistrée sur chaque prêt ; modifier le paramètre ne change pas rétroactivement les prêts existants.
- Limite de **nombre d'unités de jeu simultanément empruntées**, configurable. Un jeu de base et ses extensions non autonomes empruntés avec lui comptent pour **une unité** ; une extension autonome empruntée seule compte pour une unité. Contrôler les prêts déjà actifs avec le panier.
- Les admins peuvent changer la date d'échéance d'un prêt actif, y compris en retard, avec trace ancien/nouveau et auteur ; les membres ne peuvent ni prolonger eux-mêmes ni effectuer une demande de prolongation dans l'app.
- Un dépassement de date est un indicateur visuel, **sans suspension automatique, pénalité ni rappel email**.

### Retourner

- Depuis **« Mes emprunts actifs »**, le membre **sélectionne les copies** à rendre ; **aucune ressaisie des identifiants physiques**.
- Retour groupé ou partiel possible, transactionnel. Ne pas laisser une extension non autonome empruntée si son jeu de base nécessaire est retourné.
- Les retours sont déclarés autonomement par le membre et prennent effet **immédiatement**, sans validation administrative. Une copie redevient disponible si aucune autre cause ne la bloque.
- Un administrateur peut enregistrer le retour des prêts d'un membre désactivé.
- Aucun formulaire pour que les membres signalent une anomalie sur un exemplaire, y compris à l'occasion du retour. Signalement hors application aux responsables ; les agents d'inventaire restent habilités à relever des anomalies **dans une campagne**.
- Chaque membre consulte **tout son historique personnel** de prêts terminés (jeu, copie, dates de prêt, d'échéance et de retour) et ses prêts actifs ; jamais les historiques des autres membres.
- Les admins consultent les données nécessaires à la gestion, y compris l'identité des emprunteurs.

**Points métier à arbitrer si nécessaire avant implémentation fine :** association exacte d'une extension à une copie de base quand plusieurs copies du même jeu sont empruntées ; maintien de la dépendance si plusieurs prêts/groupes d'emprunt se croisent.

## 7. Campagnes d'inventaire physique — VALIDÉ

- Un admin crée des campagnes `DRAFT`, démarre `IN_PROGRESS`, clôt `CLOSED`. Plusieurs brouillons, plusieurs clôturées, mais **une seule campagne globale EN COURS** simultanément. Prévoir protection en base contre les démarrages concurrents (ex. index unique partiel PostgreSQL).
- Périmètre choisi par emplacements ; capture d'un **instantané** des copies concernées au démarrage. Historique et résultats gelés à la clôture, à l'exception du suivi ultérieur des **résolutions des anomalies**.
- Un admin désigne des membres actifs comme **vérificateurs pour cette campagne uniquement** ; ils peuvent vérifier, commenter et signaler, pas créer/clôturer la campagne ni modifier officiellement le stock. Historiser attribution/révocation des droits.
- Recherche d'une copie via son identifiant saisi ou navigation dans les emplacements. **Pas de scanner QR**.
- Résultats possibles : présent, déplacé, manquant, endommagé/autre anomalie, non applicable (déjà emprunté). Vérifications horodatées avec auteur ; une nouvelle vérification remplace le résultat courant sans effacer les précédentes.
- Les copies **déjà empruntées au début** sont marquées `NOT_APPLICABLE`, avec référence du prêt, hors dénominateur des copies physiquement attendues.
- Si une telle copie est **rendue pendant la campagne**, sa vérification physique devient **facultative**. Si effectuée, son résultat validé remplace le `NOT_APPLICABLE` courant et elle entre dans les statistiques de vérification ; conserver l'état initial et le suivi des mouvements.
- Les emprunts, retours, déplacements et autres mouvements de stock continuent pendant une campagne. L'analyse tient compte des **dates des mouvements** pour éviter les faux manquants.
- Les vérificateurs peuvent déclarer un exemplaire **physiquement découvert mais hors périmètre**, avec identifiant connu, lieu et commentaire. Cela ne crée ni ne modifie automatiquement le stock.
- Anomalies et état documentés **par texte uniquement**, jamais par photos.
- Les corrections d'anomalies (emplacement, état, création, restauration, etc.) sont **explicitement approuvées/appliquées par un admin**, jamais par simple relevé ou par clôture.
- La clôture reste possible avec copies non vérifiées et anomalies en suspens, après affichage du décompte et confirmation explicite.
- Les anomalies non résolues peuvent être traitées **après la clôture**, sans éditer rétroactivement les vérifications gelées. Distinguer résultat à clôture et état courant de résolution.
- Présentation des bilans **dans App CeJ uniquement** ; aucun export PDF, CSV ou Excel.
- Gérer les écritures concurrentes de vérification avec contrôle de version / verrouillage optimiste.

## 8. Événements, soirées et tables — VALIDÉ

- Les administrateurs configurent des événements récurrents (lundi par défaut, mais pas limité au lundi) et ponctuels. Heures début/fin par défaut sur la récurrence et personnalisables pour un événement. Fuseau : `Europe/Paris`. L'heure de fin peut être le lendemain.
- **Seuls les comptes actifs connectés** voient programme, tables et participants, et peuvent s'inscrire ; adhésion à jour non requise.
- Tout compte actif peut créer une table sans validation admin. Le créateur est automatiquement inscrit et occupe une place.
- Le créateur **ne peut pas quitter sa propre table** : il doit la **supprimer fonctionnellement / annuler** (historique conservé). Pas de transfert d'organisateur.
- Capacité maximale fixée à la création et modifiable par organisateur/admin, jamais sous le nombre d'inscrits actuel. Table complète = inscriptions bloquées ; désinscription = place libérée.
- Un utilisateur peut s'inscrire à plusieurs tables du **même événement même si les horaires se chevauchent**. Pas de double inscription dans une même table.
- Création/modification des inscriptions et désinscriptions possibles **jusqu'à la fin effective de l'événement**, y compris pendant son déroulement. Interdit après la fin, ou si événement/table annulé.
- Une table peut proposer soit un jeu du catalogue associatif, soit un **jeu personnel mentionné en texte libre**. Un jeu personnel **n'entre jamais dans le catalogue ni le stock**. Le propriétaire/apporteur peut être différent de l'organisateur.
- La disponibilité d'un jeu associatif affichée sur une table est **indicative** : ni réservation ni blocage de création en cas d'indisponibilité.
- Le jeu proposé peut changer même après inscriptions ; conserver les inscriptions, afficher le changement. Description de table en texte libre facultative.
- Pas de messagerie, discussion ou commentaires sur les tables.
- L'organisateur peut annuler une table avec inscrits, sans perte de l'historique.
- Un admin peut annuler un événement entier : toutes ses tables sont annulées ; conserver historique et participants. Lors de la restauration d'un événement, les **organisateurs** des tables annulées **par cet événement** peuvent individuellement réactiver leur table et retrouver les anciens participants ; ne pas réactiver automatiquement les tables, ni celles annulées volontairement.
- Désactiver un utilisateur annule ses inscriptions futures et les tables futures qu'il organise ; pas de rétablissement automatique à la réactivation.

### Notifications intégrées

- Centre de notifications **dans l'application uniquement**, pas de mails ni de push pour les événements/tables.
- Notifier les changements pertinents (annulation, restauration, réactivation, changement de jeu/capacité, etc.).
- Ne **pas notifier l'organisateur à chaque inscription/désinscription** ; l'avertir seulement quand sa table devient complète ou qu'une place se libère après avoir été complète, y compris suite à un changement de capacité.
- Statut lu/non lu, lien contextuel, rétention en jours paramétrable par admin ; purge automatique à expiration même si non lu. Les journaux d'audit métier restent indépendants.

## 9. Interface et accessibilité

- Interface française, claire, utilisable sur mobile et desktop, navigable au clavier et accessible aux lecteurs d'écran.
- Masquer les actions non autorisées mais **toujours** protéger les endpoints correspondants.
- Afficher des états explicites : disponible, emprunté, en retard, bloqué, archivé (pour admins), annulé, complet, etc.
- Formulaires avec validation lisible et messages d'erreur exploitables ; confirmation des opérations destructrices ou à large impact.
- Ne jamais présenter une date prévisionnelle de retour comme une réservation/garantie.
- Ne pas divulguer dans l'interface ni dans les payloads membres le nom, email ou ID de l'emprunteur d'une copie.
- Traiter les changements de fuseau et d'heure été/hiver sur les événements et échéances.
- Pour les images de couverture privées, préférer des ressources servies sous autorisation plutôt que des URL publiques permanentes.

## 10. Directives d'implémentation pour l'agent

### Avant de coder

1. Lire ce fichier et examiner le dépôt réel (`README`, modules, règles de style, migrations, tests) ; **le code existant prime pour comprendre l'état actuel, mais pas pour écraser une règle métier validée sans signalement**.
2. Reformuler la fonctionnalité demandée et identifier les règles ci-dessus concernées.
3. Identifier les dépendances, autorisations, états, opérations concurrentes et scénarios limites.
4. Si une décision métier essentielle manque, signaler clairement le point à arbitrer ; ne pas lui attribuer silencieusement une valeur par défaut.

### Pendant l'implémentation

- Privilégier changements petits, cohérents et testables ; ne pas restructurer des parties non concernées sans nécessité.
- Valider les entrées **au backend** et produire des réponses d'erreur stables.
- Mettre les invariants critiques dans le domaine/service métier, avec contraintes en base lorsque pertinent.
- Pour les actions atomiques (panier d'emprunts, retour groupé, création multiple, archivages en cascade), utiliser transaction et verrouillage/contraintes adaptés ; éviter les contrôles `check-then-act` non protégés.
- Préserver audit, historicité et relations lors des archivages/restaurations.
- Empêcher les accès horizontaux entre membres (IDOR), y compris pour l'historique et les documents/images.
- Garder les DTO publics distincts des DTO administrateur ; ne pas « masquer côté React » des champs sensibles déjà transmis par API.
- Ne pas ajouter de dépendances ou de services payants sans justification et accord.

### Tests obligatoires par changement pertinent

- **Unitaires** : règles de permissions, compteurs d'unités empruntées, extensions, feuilles de localisation, détection d'identifiants.
- **Intégration** : transactions atomiques, contraintes d'unicité, archivages en cascade, restauration non cascade, unicité d'une campagne active, accès SQL et migrations.
- **Sécurité** : visiteurs refusés, compte désactivé, membre vs admin, accès à l'historique d'autrui, absence de fuite d'identité d'emprunteur.
- **Concurrence** : deux emprunts simultanés de la même copie, modification d'identifiant, inventaire et mouvements, création multiple.
- **Frontend/E2E** : panier d'emprunt, retours groupés/partiels, recherche/tri/pagination, inscriptions aux tables, inventaire.
- **Horaires** : événement traversant minuit et transition heure d'été/hiver.

Ne déclarer une tâche « terminée » que si tests applicables exécutés ou limitations explicitement signalées. Ne pas inventer de résultats de tests.

### Réponse attendue après une tâche

Présenter brièvement : ce qui a changé, fichiers ou modules concernés, tests lancés et résultats, éventuelles limitations ou décisions ouvertes. S'il s'agit d'une tâche de conception, distinguer les règles validées des recommandations.

## 11. Hors périmètre explicite du MVP

- Inscription publique / création autonome de compte.
- Catalogue public, consultation des soirées sans compte.
- Réservations de jeux, listes d'attente, pénalités ou suspension automatique pour retards.
- Rappels email de retard ; emails/push pour changements de tables.
- Chat/commentaires sur les tables ; formulaire de signalement d'état de copie par les membres.
- Photos des anomalies ou de l'état des exemplaires (les couvertures restent permises).
- Liste structurée exhaustive de composants/quantités des boîtes.
- Import/export CSV, Excel ou PDF des inventaires ; import en masse du stock par fichier.
- Étiquettes imprimables, génération/scan de QR code ou codes-barres.
- Fusion de fiches de jeux et transfert de copies entre fiches.
- Modifications administratives groupées de copies existantes.
- Scan de stock automatisé ; correction automatique des anomalies d'inventaire.
- Publication obligatoire sur les stores mobiles.

## 12. Registre des décisions encore ouvertes

Ne pas déduire un choix de ce document pour ces points :

1. **Unicité des noms d’emplacements** : décision prise le 16 novembre 2026 — doublons autorisés.
2. **Stack précise et versions** : React/TypeScript + Spring Boot + PostgreSQL sont une orientation proposée, pas une validation technique définitive.
3. **Longueur maximale** des identifiants d'inventaire et des textes libres (plusieurs maxima ont été proposés, sans validation spécifique).
4. **Nombre de résultats par page** : décision prise le 16 novembre 2026 — 20 jeux par page.
5. **Association fine des extensions et des emprunts multiples** lorsque plusieurs copies de jeux de base identiques sont détenues.
6. **Stratégie exacte de connexion/session**, gestion des cookies et renouvellement, solution d'email sortant et hébergement.
7. **Politique RGPD de conservation et d'effacement** des données personnelles, distincte de la conservation des historiques métier.
8. **Sélection des exemplaires à emprunter** : parcours à définir sans saisie d’identifiant d’inventaire, désormais automatique et masqué (arbitrage du 16 novembre 2026).

Pour toute ambiguïté, noter la question, l'impact et une option réversible si elle permet d'avancer sans compromettre les règles validées.

---

**Règle finale de l'agent :** implémenter exactement le produit arbitré par Cité en jeux. L'agent peut proposer des améliorations et remettre en question un choix risqué, mais doit les présenter séparément et attendre validation avant de changer les exigences.
