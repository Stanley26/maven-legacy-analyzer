# Maven Legacy Analyzer

Analyseur local Java 21 pour explorer les dépendances de projets Maven. Il découvre les POM à toute profondeur, utilise le **wrapper du projet analysé** et produit des preuves Maven, un inventaire JSON et un rapport HTML consultable sans serveur.

## Démarrer

Prérequis : JDK 21. Maven est téléchargé par le wrapper de ce dépôt au premier lancement.

```powershell
.\mvnw.cmd verify
java -jar target/maven-legacy-analyzer.jar --help
java -jar target/maven-legacy-analyzer.jar scan C:/projects --output C:/analyses/scan-001
```

Linux/macOS : remplacer `.\mvnw.cmd` par `./mvnw`.

Pour un inventaire des déclarations sans lancer Maven :

```powershell
java -jar target/maven-legacy-analyzer.jar scan C:/projects --inventory-only --output C:/analyses/inventaire-001
```

Le dossier de sortie doit être neuf ou vide. Les POM des projets ne sont jamais réécrits. Les appels Maven peuvent télécharger dans le cache, exécuter les extensions du projet et produire les effets habituels de son wrapper : utiliser des dépôts de confiance. Aucun `install`, `package` ou `deploy` n'est lancé sur les projets analysés.

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
java -jar target/maven-legacy-analyzer.jar scan C:/projects --config config/scan.local.json --output C:/analyses/scan-002
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

Cette première version fournit l'inventaire et les preuves de résolution ; certaines analyses avancées restent à implémenter.

- Les commentaires d'origine de l'effective POM sont exploités. L'historique complet des candidats perdants, des overrides et des propriétés des parents/BOM externes n'est **pas** encore reconstruit. Les déclarations locales brutes restent disponibles.
- Les BOM ne sont pas confondus avec des bibliothèques consommées. La version d'une propriété locale n'est pas supposée remplacer une propriété interne à un BOM importé.
- Les profils bruts sont inventoriés, sans être présentés comme actifs. Le résultat effectif est celui de Maven ; le détail des profils activés implicitement reste à collecter.
- Chaque POM est interrogé avec `-N`, hors résolution complète du reactor. Les dépendances entre modules doivent déjà être disponibles dans les dépôts/cache ; sinon le résultat est partiel. Un mode reactor est prévu.
- Pas de simulation de mise à jour, de modification des POM, de suppression automatique des overrides, d'inspection des archives ni de preuve runtime.
- Un artefact Maven local qui a le même GAV qu'un artefact publié n'est pas forcément le même binaire. Les consommateurs sont des correspondances d'identité Maven, pas une preuve de provenance Git.
- Sur Windows, les chemins/arguments contenant des caractères de contrôle shell (`%`, `&`, `!`, etc.) sont refusés explicitement ; les espaces sont pris en charge.
- Les scripts Maven peuvent avoir leurs propres exigences ; les versions de plugins peuvent être adaptées à un ancien wrapper. Aucune compatibilité avec toutes les versions historiques de Maven n'est revendiquée.

Code de sortie : `0` = collecte demandée terminée sans échec ; `2` = échec partiel, entrée invalide ou aucun POM. `RESOLVED` signifie que l'effective POM et l'arbre ont été collectés, pas que l'application fonctionne à l'exécution.

## Organisation

Voir [les exigences fonctionnelles](docs/REQUIREMENTS.md), [l'architecture](docs/ARCHITECTURE.md) et [la feuille de route](docs/ROADMAP.md). Les tests unitaires s'exécutent avec `mvnw verify`. Le workflow GitHub Actions les exécute sur Windows et Linux et vérifie aussi le laboratoire réel.

Sources techniques : [Maven dependency mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html), [effective-pom](https://maven.apache.org/plugins/maven-help-plugin/effective-pom-mojo.html), [dependency:tree](https://maven.apache.org/plugins/maven-dependency-plugin/tree-mojo.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/).
