# Architecture

`Main` orchestre un scan séquentiel. `Scanner` découvre les POM. `PomReader` lit les déclarations avec positions et commentaires d'origine. `Configuration` sélectionne l'environnement par dépôt. `MavenRunner` exécute le wrapper avec un délai maximal. `Collector` collecte l'effective POM et l'arbre JSON. `ProvenanceCollector` collecte le contexte et les sources citées par Maven ; `PomGraphCollector` complète récursivement les parents/imports et les POM du graphe sélectionné. `Provenance` construit les explications sans réimplémenter la médiation. `Analysis` établit les correspondances entre consommateurs et artefacts locaux. `ReportWriter` et `ProvenanceWriter` écrivent les pages autonomes.

## Contrat de collecte

Pour chaque POM valide, appeler le wrapper le plus proche depuis son propre dossier, avec `-B -N -f <POM absolu>`. Passer les settings/JDK/profils configurés explicitement, sans remplacer les paramètres propres au wrapper. Conserver la configuration du projet. Les goals d'analyse et les fichiers de sortie sont distincts ; un échec d'un goal n'efface pas les données de l'autre.

Par défaut, les sorties sont écrites dans `reports/scan-<date>/`, sous le dossier de l'analyseur, indépendamment du dossier courant du terminal. `--output` garde la priorité lorsqu'il est fourni. Un dossier neuf évite la réutilisation accidentelle d'un ancien résultat.

`ReportLayout` organise les preuves sous `evidence/<projet>/<module>/`. Le module racine est nommé `root` ; les composants d'un chemin imbriqué sont joints par `--`. Les noms sont normalisés pour les systèmes de fichiers et les collisions sont désambiguïsées avec des suffixes numériques, sans hash opaque. Les liens sont relatifs au dossier du rapport et restent valides lorsque l'ensemble du rapport est déplacé. Les processus enfants sont arrêtés au timeout.

## Données

`analysis.json`, version de schéma `0.2`, contient : racine, mode, date, modules, erreurs de découverte, consommateurs internes et compteurs. Chaque module contient déclarations brutes, modèle effectif éventuel, dépendances sélectionnées avec chemins, contexte, invocations, preuves, anomalies, sources copiées, relations de POM et explications.

Les champs `projectName` et `modulePath` portent le nom du dossier projet et le chemin du POM relatif à ce projet. Leur assemblage fournit le titre HTML même pour un scan d'un seul dépôt. `id` reste relatif à la racine du scan pour préserver les correspondances entre modules et consommateurs.

L'origine d'une version effective est reprise du commentaire Maven lorsqu'il existe. Les numéros de ligne des déclarations brutes désignent le POM d'origine ; ceux du modèle effectif désignent le fichier effectif généré. `origin` contient la localisation d'origine fournie par Maven.

`origin` désigne exclusivement l'origine du champ version ; `declarationOrigin` conserve celle de l'artefact. Elles ne sont pas interchangeables. Une liaison de source exige une identité de dépendance, une origine Maven et un numéro de ligne concordants et non ambigus. Une propriété du consommateur n'est jamais appliquée par l'analyseur à un modèle externe. Les candidats évincés et la médiation complète restent inconnus.

Le graphe part des sources locales et des dépendances sélectionnées. Chaque POM brut obtenu ajoute ses parents et imports à la file. Une identité déjà vue termine les cycles. Les imports de profils sont des déclarations à collecter, sans preuve d'activation. Les coordonnées littérales utilisent le cache indiqué par Maven ; les absentes sont récupérées avec `dependency:get`. Les expressions sont évaluées par `help:evaluate`, dans le contexte du modèle concerné. Aucune configuration de connexion parallèle n'est créée.

## Réutilisation des analyses

`SavedReports` lit les schémas 0.1/0.2. Il reconstruit les champs de localisation disponibles depuis les XML sauvegardés et vérifie les empreintes des copies sources. La régénération écrit les pages et leurs fichiers de présentation ; elle ne modifie ni la date du scan ni ses fichiers de preuve ou son JSON original, et ne consulte jamais les dépôts originaux. `--output` importe une copie complète dans un dossier neuf. Une donnée absente reste explicitement inconnue.

`ReportLibrary` maintient `reports/catalog.json` et l'accueil `index.html` dans le dossier de l'analyseur, exclus de Git. `refresh-reports` retrouve le catalogue et les dossiers sous `reports/` ; un échec n'empêche pas de régénérer les autres rapports. Les archives externes sont enregistrées avec `report`. Les liens à l'intérieur d'un rapport restent relatifs et portables.

## Interface locale

`ExplorerWriter` produit `index.html`, un index compact avec dictionnaires de coordonnées et de chemins dans `explorer/data.js` et `data.json`, ainsi qu'un script de détails par module. L'index représente chaque usage par module, coordonnées, scope, chemin, indicateur d'override documenté, catégorie de source et position dans les dépendances du module. Les modèles bruts complets restent dans l'archive originale ; les détails sont chargés à la demande. `details.html` conserve le rapport exhaustif.

Le moteur JavaScript sans dépendance externe agrège les projets distincts, filtre les observations, calcule les consommateurs et compare les instantanés. Les tables de l'explorateur sont paginées. Les projets sont identifiés par dépôt dans un instantané ; la comparaison rapproche des noms projet/module uniques et ne compare que les modules `RESOLVED` des deux côtés. Les chemins transitoires des checkouts n'interviennent pas dans ce rapprochement.

`LocalServer` régénère les présentations puis sert uniquement les dossiers des rapports, sur `127.0.0.1`. Il accepte GET/HEAD, vérifie Host et refuse les chemins hors rapport et liens symboliques. `/api/reports` expose le catalogue ; aucun endpoint ne lance Maven, modifie un projet ou téléverse des données. Le même explorateur s'ouvre par fichier sans serveur ; seule la sélection de cible diffère (fichier local au lieu du catalogue HTTP). Les données sont échappées avant insertion dans l'HTML ou les scripts.

## Limites d'exécution

Une exécution Maven peut charger des extensions, télécharger des artefacts et appliquer les effets propres au wrapper. Le code de l'analyseur ne modifie pas les POM et ne lance pas de phase de compilation/installation sur les projets analysés. L'absence de modification des sources n'est pas une sandbox des builds.

Les XML interdisent DTD et entités externes. L'HTML échappe les données des projets. Les logs restent locaux ; ils ne sont pas garantis anonymisés.
