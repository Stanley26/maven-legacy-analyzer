# Maven Legacy Analyzer

Analyseur local Java 21 pour explorer les dépendances de projets Maven. Il découvre les POM à toute profondeur, utilise le **wrapper du projet analysé** et produit des preuves Maven, un inventaire JSON et un rapport HTML consultable sans serveur.

Ouvrir **`index.html` à la racine de l'analyseur** pour retrouver les analyses enregistrées. Cette page est créée après un scan ou une régénération. Elle et le catalogue local sont exclus de Git.

## Démarrer

Prérequis : JDK 21. Maven est téléchargé par le wrapper de ce dépôt au premier lancement.

```powershell
.\mvnw.cmd verify
java -jar target/maven-legacy-analyzer.jar --help
java -jar target/maven-legacy-analyzer.jar scan C:/projects
java -jar target/maven-legacy-analyzer.jar serve
```

Linux/macOS : remplacer `.\mvnw.cmd` par `./mvnw`.

Ouvrir ensuite **http://127.0.0.1:8080**. `serve` régénère les vues depuis les analyses enregistrées et ouvre la plus récente ; le sélecteur permet de changer d'analyse. Aucun appel Maven n'est lancé. Le serveur écoute uniquement sur le poste local. `Ctrl+C` l'arrête ; `--port 8081` choisit un autre port. Un dossier de rapport peut être fourni pour ne servir que cette analyse : `java -jar target/maven-legacy-analyzer.jar serve reports/mon-scan`.

## Explorer le parc

- **Vue du parc** : projets distincts, POM, composants, familles présentes et points à examiner. Filtres par dépendance, groupId, version, scope, override documenté et diagnostics.
- **Composants** : versions observées et nombre de projets consommateurs. Cliquer sur une version pour consulter les modules, scopes, origines de déclaration et preuves. La surface en aval contient les dépendances dont le chemin sélectionné passe par le composant.
- **Pourquoi ?** : chemin d'introduction ciblé, déclarations, propriétés et redéfinitions documentées, avec liens vers les lignes XML. Les faits de gestion de version restent distincts du graphe transitif.
- **Projets** : modules avec leurs noms complets, état de collecte et dépendances filtrables.
- **Bibliothèques partagées** : consommateurs externes au dépôt fournisseur, directs, indirects uniquement et total distinct. Les ensembles ne sont pas additionnés avec doublons.
- **Comparaison** : sélectionner une autre analyse enregistrée, ou ouvrir son `analysis.json` local. Les écarts de versions/scopes peuvent être filtrés par projet ou dépendance, et par override documenté à examiner.

Les tableaux de l'explorateur sont paginés par 40 lignes. Les preuves détaillées d'un module sont chargées à la demande. Les compteurs de composants regroupent par `groupId:artifactId` ; le détail conserve version, type et classifier. Un même projet peut appartenir à plusieurs compteurs de versions. « Interne » signifie qu'un GAV résolu correspond à un module du parc, sans preuve d'identité du binaire.

Les familles sont les groupId observés, sans composant propre à une organisation codé en dur. « Override documenté » couvre les redéfinitions de propriétés prouvées par les sources collectées, et ne prétend pas recenser tous les overrides possibles. La dispersion de versions entre projets n'est pas automatiquement un conflit.

La comparaison rapproche les modules par nom de projet et chemin du POM, puis les dépendances par groupId, artifactId, type et classifier. Seuls les modules résolus des deux côtés, avec un nom non ambigu, sont comparables. Un module absent, renommé ou partiel reste **non comparable** ; ses dépendances ne sont pas annoncées comme supprimées. Les écarts ne prouvent pas une compatibilité ou un blocage de migration. L'acquisition et la simulation du modèle d'une version cible restent à implémenter.

L'ouverture directe du `index.html` d'un rapport fonctionne aussi sans serveur, avec les fichiers `explorer/` à côté. La comparaison utilise alors le sélecteur de fichier local. Le rapport exhaustif reste accessible dans `details.html`. Aucun CDN ni service externe n'est utilisé.

Pour un inventaire des déclarations sans lancer Maven :

```powershell
java -jar target/maven-legacy-analyzer.jar scan C:/projects --inventory-only
```

Les rapports sont placés par défaut dans le dossier de l'analyseur, à côté de son `pom.xml` lorsqu'il est lancé depuis le dépôt, ou à côté du JAR pour une distribution portable. L'emplacement ne dépend pas du dossier courant du terminal.

```text
maven-legacy-analyzer/
  index.html                 # accueil de toutes les analyses
  reports/
    scan-2026-01-02_03-04-05-000/
      index.html
      details.html
      analysis.json
      explorer/              # index compact et scripts de présentation régénérables
      evidence/
        project-a/
          root/effective-pom.xml
          root/logs/effective-pom.log
          ear/effective-pom.xml
          common/effective-pom.xml
```

## Réutiliser une analyse après une mise à jour

Conserver le dossier complet de chaque rapport, avec `analysis.json` et `evidence/`. Après avoir mis à jour et reconstruit l'analyseur, régénérer les pages avec :

```powershell
.\mvnw.cmd verify
java -jar target/maven-legacy-analyzer.jar refresh-reports
```

`refresh-reports` retrouve les analyses du dossier `reports/` et celles enregistrées dans le catalogue local. **Cette commande ne lance aucun wrapper/Maven, ne nécessite aucun accès réseau et ne lit pas les dépôts sources.** Elle conserve le JSON original, les XML et les logs du scan ; elle régénère les pages HTML et leurs fichiers de présentation dans `explorer/`. La compilation de l'analyseur, elle, utilise son propre wrapper et peut nécessiter Maven Central.

Pour un ancien rapport situé ailleurs, l'enregistrer et régénérer ses pages sur place :

```powershell
java -jar target/maven-legacy-analyzer.jar report C:/analyses/scan-existant
```

Ou en importer une copie complète dans l'analyseur :

```powershell
java -jar target/maven-legacy-analyzer.jar report C:/analyses/scan-existant --output reports/scan-importe
```

Le dossier de copie doit être neuf ou vide. Les formats de données `0.1` et `0.2` sont pris en charge, y compris les anciens chemins de preuves. Les nouvelles vues exploitent les preuves déjà sauvegardées : elles ne peuvent pas recréer les parents, profils ou contextes absents d'un ancien scan. Le rapport conserve sa date d'analyse et indique qu'il s'agit d'une présentation régénérée. Pour observer des changements dans les projets, lancer un nouveau `scan`.

Les liens entre les pages d'un rapport sont relatifs : déplacer le dossier complet conserve ces liens. L'accueil référence aussi les rapports externes par leur emplacement local ; après leur déplacement, les enregistrer à leur nouvelle adresse avec `report`.

## Pourquoi cette version ?

Chaque dépendance résolue possède un lien vers une explication : chemin direct ou transitif, version effective, déclaration versionnée locale/héritée/gérée lorsqu'elle est identifiable, et propriété effective éventuelle. Les preuves XML s'ouvrent à la ligne concernée. Les noms, types et classifiers sont pris en compte ; les origines ambiguës restent signalées.

Les nouveaux scans conservent tous les POM bruts locaux découverts. La collecte Maven ajoute les POM des dépendances résolues et parcourt récursivement les parents et les imports de BOM présents dans les modèles bruts. Un BOM reste ainsi collecté même si ses entrées ont été remplacées dans le modèle effectif. Les imports déclarés dans les profils sont conservés avec leur profil, sans être présentés comme actifs. Chaque source exploitable est copiée sous `evidence/<projet>/<module>/sources/`, avec une empreinte SHA-256.

Maven fournit le cache local utilisé. Lorsqu'un POM manque dans ce cache, l'analyseur demande `dependency:get` au **wrapper du projet, avec les mêmes settings, profils, propriétés et JDK**. Les miroirs, dépôts et accès Artifactory configurés restent gérés par Maven. L'analyseur ne parcourt pas le catalogue complet d'un serveur et ne demande aucun identifiant supplémentaire. Les coordonnées contenant des propriétés sont évaluées par Maven dans le contexte du consommateur pour l'héritage, ou du modèle externe pour les imports propres à ce modèle. Un résultat non résolu reste signalé, sans version supposée.

`pomRelations` décrit chaque relation et son résultat (`COLLECTED`, `UNRESOLVED`, `UNAVAILABLE`). Les cycles et références répétées sont dédupliqués par coordonnées dans la collecte d'un module. Un parent dont Maven n'expose pas le chemin est recherché par coordonnées dans le cache et marqué `PARENT_CACHE`. Les sources `*_MAVEN` ont nécessité une demande de récupération au wrapper. Une copie du cache ou un téléchargement n'atteste pas à lui seul que le serveur était l'origine initiale d'un artefact déjà résolu.

Le rapport sépare l'origine de la déclaration `${version}` et celle de la propriété effective. Il indique les redéfinitions observées dans les ancêtres copiés. Les propriétés internes d'un BOM restent dans le contexte de ce modèle ; une propriété homonyme du consommateur ne lui est pas appliquée. Les cas non démontrés et la médiation complète restent explicitement ouverts.

Le contexte contient les versions Maven/JDK annoncées lors de la collecte effective et la sortie de `help:active-profiles`, distincte des profils demandés. Ces collectes ajoutent des invocations Maven. Un échec de provenance ne supprime pas l'effective POM ou l'arbre déjà obtenus ; `context.provenanceCollection` indique `PARTIAL` et les erreurs restent visibles.

Les nouveaux scans regroupent les journaux Maven dans `evidence/<projet>/<module>/logs/`. Les fichiers `coordinate-*.log` retracent l'évaluation des expressions utilisées dans les coordonnées de parents ou de BOM ; leur présence n'indique pas une erreur. Les résultats de diagnostic correspondants (`coordinate-*.txt`, recherche des parents et du cache) sont dans le même dossier. Les expressions d'un même contexte sont regroupées dans des appels de taille limitée ; les valeurs des modèles externes ne sont pas partagées entre différents contextes Maven. `context.pomGraph` indique le nombre de propriétés et de lots évalués. Les anciens rapports conservent leurs chemins et restent consultables sans déplacement de leurs preuves.

Chaque analyse crée un dossier daté. Les titres HTML affichent `project-a/ear/pom.xml` et les coordonnées Maven sans ouvrir les détails. `root` représente le POM à la racine du projet ; les chemins imbriqués utilisent `--` (ex. `app--webapp`). Si deux noms normalisés sont identiques, un suffixe numérique les distingue. Les fichiers source `pom.xml` ne sont pas renommés.

`--output <chemin>` permet de choisir un autre dossier, neuf ou vide. Les anciens rapports ne sont pas déplacés ; `report` ou `refresh-reports` actualise leur présentation sans nouveau scan. Les appels Maven peuvent télécharger dans le cache, exécuter les extensions du projet et produire les effets habituels de son wrapper : utiliser des dépôts de confiance. Aucun `install`, `package` ou `deploy` n'est lancé sur les projets analysés.

## Fonctionnalités de cette version

- Découverte récursive des `pom.xml`, y compris ceux absents des `<modules>` ; `.git`, liens symboliques et dossier de sortie non parcourus. Les POM sous `target` sont inventoriés, sans exécution Maven.
- Conservation des coordonnées brutes, parents, modules, profils, propriétés, dépendances, exclusions et entrées `dependencyManagement`, avec numéros de ligne.
- Wrapper le plus proche du module, limité au dépôt concerné. Aucun remplacement silencieux par le Maven système.
- `help:effective-pom` avec commentaires d'origine Maven et `dependency:tree` JSON, tous scopes. Versions des plugins fixées et configurables.
- Conservation du chemin d'introduction de chaque dépendance sélectionnée.
- Correspondances avec les composants internes locaux sur `groupId:artifactId:version` exacts, consommateurs directs et transitifs.
- Signalement des différences de versions pour les groupes explicitement déclarés dans `alignmentGroups`.
- Échecs partiels, timeout par commande, preuves et logs séparés pour chaque POM.
- Rapport HTML avec recherche et détails, JSON structuré, sans CDN, télémétrie ni IA.

## Java, wrappers et settings

L'analyseur nécessite Java 21. Le processus Maven de chaque dépôt hérite de l'environnement par défaut ; un `javaHome` distinct peut sélectionner un autre JDK.

Par défaut, l'outil laisse le wrapper, `.mvn/maven.config` et Maven appliquer leurs settings habituels. **Un `settings.xml` simplement posé dans un dossier n'est pas automatiquement sélectionné.** S'il n'est pas déjà référencé par le wrapper/la configuration Maven, déclarer `settings` dans la configuration de l'analyseur. Ne pas doubler une option `--settings` déjà définie avec une autre valeur.

```powershell
java -jar target/maven-legacy-analyzer.jar scan C:/projects --config config/scan.local.json
```

Voir [config/example.json](config/example.json). Les clés de `repositories` sont les chemins relatifs depuis la racine scannée ; pour scanner un dépôt unique, utiliser `"."`. Les chemins `settings`, `globalSettings` et `javaHome` relatifs sont résolus depuis la racine de ce dépôt. Les paramètres du dépôt remplacent ceux de `defaults` ; listes et dictionnaires sont remplacés entièrement.

Conserver les secrets dans les settings appropriés ; ne pas transmettre de mot de passe en propriété Maven. Les rapports et logs peuvent contenir des noms, chemins et configurations sensibles. Ils sont destinés à une consultation locale et les dossiers de sortie standard sont exclus de Git. L'outil n'exporte pas les effective-settings ni les valeurs des propriétés de commande dans son contexte JSON.

`--offline` passe `-o` à Maven : il faut avoir préparé le cache et la distribution du wrapper auparavant. Un wrapper peut toujours tenter son propre téléchargement initial.

## Laboratoire synthétique

Les fixtures couvrent des parents publiés absents du checkout, plusieurs lignées, un BOM importé, un module profond non listé, des scopes `provided`, une bibliothèque partagée et un override hérité.

**Tous les JAR du laboratoire sont des doublures vides**, avec des coordonnées fictives `com.example.*`. Les scripts `scripts/run-lab.ps1` et `scripts/run-lab.sh` imposent un cache isolé pour éviter de mélanger ces fixtures avec les dépendances de projets réels.

```powershell
.\scripts\run-lab.ps1
```

Le script construit l'analyseur, génère le laboratoire dans `target`, lance une analyse réelle via les wrappers et vérifie les résultats attendus. Au premier passage, l'accès à Maven Central est nécessaire pour les plugins.

## Ce qui n'est pas encore implémenté

L'analyseur fournit l'inventaire et les preuves de résolution ; certaines analyses avancées restent à implémenter.

- L'historique complet des candidats perdants, des overrides, des exclusions et des substitutions n'est **pas** reconstruit. Les sources copiées et les origines d'éléments expliquent uniquement les faits disponibles. Les propriétés de commande ne font pas l'objet d'un historique complet de priorité.
- Les BOM ne sont pas confondus avec des bibliothèques consommées. La version d'une propriété locale n'est pas supposée remplacer une propriété interne à un BOM importé.
- Les profils bruts sont inventoriés séparément de la liste des profils actifs fournie par Maven. Les raisons détaillées de leur activation ne sont pas reconstituées.
- Les sources dont les coordonnées utilisent des expressions non résolues, notamment dans des profils inactifs, certains layouts de cache/SNAPSHOT ou des POM inaccessibles peuvent rester incomplètes. Les échecs sont conservés dans le graphe. La collecte inclut les POM du graphe sélectionné ; elle ne cherche pas toutes les versions évincées ni les POM des plugins de build.
- Chaque POM est interrogé avec `-N`, hors résolution complète du reactor. Les dépendances entre modules doivent déjà être disponibles dans les dépôts/cache ; sinon le résultat est partiel. Un mode reactor est prévu.
- Pas de simulation de mise à jour, de modification des POM, de suppression automatique des overrides, d'inspection des archives ni de preuve runtime.
- Un artefact Maven local qui a le même GAV qu'un artefact publié n'est pas forcément le même binaire. Les consommateurs sont des correspondances d'identité Maven, pas une preuve de provenance Git.
- Sur Windows, les chemins/arguments contenant des caractères de contrôle shell (`%`, `&`, `!`, etc.) sont refusés explicitement ; les espaces sont pris en charge.
- Les scripts Maven peuvent avoir leurs propres exigences ; les versions de plugins peuvent être adaptées à un ancien wrapper. Aucune compatibilité avec toutes les versions historiques de Maven n'est revendiquée.

Code de sortie : `0` = collecte demandée terminée sans échec ; `2` = échec partiel, entrée invalide ou aucun POM. `RESOLVED` signifie que l'effective POM et l'arbre ont été collectés, pas que l'application fonctionne à l'exécution.

## Organisation

Voir [les exigences fonctionnelles](docs/REQUIREMENTS.md), [l'architecture](docs/ARCHITECTURE.md) et [la feuille de route](docs/ROADMAP.md). Les tests Java s'exécutent avec `mvnw verify`. Les tests des agrégations et comparaisons de l'interface s'exécutent avec `node --test src/test/js/explorer.test.cjs` (Node 22, sans installation de paquets). Node n'est pas nécessaire pour lancer l'analyseur. GitHub Actions exécute les deux suites sur Windows et Linux, ainsi que le laboratoire Maven réel.

Sources techniques : [Maven dependency mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html), [effective-pom](https://maven.apache.org/plugins/maven-help-plugin/effective-pom-mojo.html), [dependency:tree](https://maven.apache.org/plugins/maven-dependency-plugin/tree-mojo.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).
