# Architecture

`Main` orchestre un scan séquentiel. `Scanner` découvre les POM. `PomReader` lit les déclarations avec positions et commentaires d'origine. `Configuration` sélectionne l'environnement par dépôt. `MavenRunner` exécute le wrapper avec un délai maximal. `Collector` collecte l'effective POM et l'arbre JSON. `Analysis` construit les correspondances entre consommateurs et artefacts locaux. `ReportWriter` écrit JSON et HTML autonomes.

## Contrat de collecte

Pour chaque POM valide, appeler le wrapper le plus proche depuis son propre dossier, avec `-B -N -f <POM absolu>`. Passer les settings/JDK/profils configurés explicitement, sans remplacer les paramètres propres au wrapper. Conserver la configuration du projet. Les goals d'analyse et les fichiers de sortie sont distincts ; un échec d'un goal n'efface pas les données de l'autre.

Les fichiers de preuve sont écrits dans un dossier neuf, sous `evidence/<empreinte du chemin du POM>/`. Cela évite les collisions et la réutilisation accidentelle d'un résultat d'une ancienne analyse. Les processus enfants sont arrêtés au timeout.

## Données

`analysis.json`, version de schéma `0.1`, contient : racine, mode, date, modules, erreurs de découverte, consommateurs internes et compteurs. Chaque module contient déclarations brutes, modèle effectif éventuel, dépendances sélectionnées avec chemins, contexte, invocations, preuves et anomalies.

L'origine d'une version effective est reprise du commentaire Maven lorsqu'il existe. Les numéros de ligne des déclarations brutes désignent le POM d'origine ; ceux du modèle effectif désignent le fichier effectif généré. `origin` contient la localisation d'origine fournie par Maven.

Les candidats évincés, lignées complètes de parents/BOM et raisons détaillées d'override feront l'objet d'une extension du schéma. Une origine absente ne doit pas être fabriquée.

## Limites d'exécution

Une exécution Maven peut charger des extensions, télécharger des artefacts et appliquer les effets propres au wrapper. Le code de l'analyseur ne modifie pas les POM et ne lance pas de phase de compilation/installation sur les projets analysés. L'absence de modification des sources n'est pas une sandbox des builds.

Les XML interdisent DTD et entités externes. L'HTML échappe les données des projets. Les logs restent locaux ; ils ne sont pas garantis anonymisés.
