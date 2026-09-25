# Feuille de route

## Livré dans 0.1

- Inventaire récursif, wrapper/configuration par dépôt, modèles brut/effectif.
- Arbre Maven sélectionné, scopes et chemins, rapport HTML/JSON, consommateurs exacts.
- Isolation des échecs, timeout, tests et laboratoire synthétique.

## Livré dans 0.2

- Vue « Pourquoi cette version ? », liens vers les XML numérotés, séparation déclaration/propriété, redéfinitions observées et limites explicites.
- Copies des POM bruts locaux, POM des dépendances résolues, parents et imports BOM récursifs ; récupération des absents par le wrapper et les settings du projet.
- Graphe des sources avec résultats de collecte, empreintes SHA-256, Maven/JDK observés et liste des profils actifs.
- Accueil `index.html` dans l'analyseur ; régénération/import de rapports sauvegardés sans Maven, compatible avec les données 0.1.
- Laboratoire avec BOM imbriqué, parent du BOM, propriété de BOM isolée, import de profil inactif à récupérer et profil implicite actif.

## Terminer la V1 de provenance

- Approfondir l'attestation des sources réellement utilisées (dépôt distant, SNAPSHOT, relocalisation) et les expressions non résolues.
- Reconstituer les propriétés et déclarations candidates, médiation des conflits, exclusions, BOM importés et raisons vérifiées des versions évincées.
- Ajouter un mode reactor qui respecte les modules frères sans installer ni modifier les applications.
- Compléter la reproductibilité : empreintes de configuration sans secrets, propriétés externes et raisons d'activation des profils.
- Ajouter des vues par groupe de composants, overrides, historique et comparaison entre scans.
- Étendre les fixtures aux SNAPSHOT, relocalisations et changements de portée transitifs.

## Analyses avancées

- Comparer différentes versions de composants et identifier les projets affectés.
- Simuler une mise à jour dans une copie isolée et expliciter les différences.
- Examiner les archives produites ; intégrer ultérieurement des observations runtime.
- Ne conclure à la compatibilité qu'après compilation, déploiement et tests appropriés.
