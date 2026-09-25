# Feuille de route

## Livré dans 0.1

- Inventaire récursif, wrapper/configuration par dépôt, modèles brut/effectif.
- Arbre Maven sélectionné, scopes et chemins, rapport HTML/JSON, consommateurs exacts.
- Isolation des échecs, timeout, tests et laboratoire synthétique.

## Terminer la V1 de provenance

- Inventorier récursivement les POM parents/BOM/dépendances effectivement utilisés, avec origine checkout/cache/dépôt, sans exporter de secrets.
- Reconstituer les propriétés et déclarations candidates, médiation des conflits, exclusions, BOM importés et raisons vérifiées des versions évincées.
- Ajouter un mode reactor qui respecte les modules frères sans installer ni modifier les applications.
- Collecter Maven/JDK/profils actifs réels et empreintes des sources/configurations pour la reproductibilité.
- Ajouter des vues par groupe de composants, overrides, historique et comparaison entre scans.
- Étendre les fixtures aux importations BOM imbriquées, propriétés de BOM non héritées, classifiers, profils et changements de portée transitifs.

## Analyses avancées

- Comparer différentes versions de composants et identifier les projets affectés.
- Simuler une mise à jour dans une copie isolée et expliciter les différences.
- Examiner les archives produites ; intégrer ultérieurement des observations runtime.
- Ne conclure à la compatibilité qu'après compilation, déploiement et tests appropriés.
