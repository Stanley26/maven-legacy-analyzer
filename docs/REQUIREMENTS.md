# Exigences fonctionnelles

## Objectif

Fournir un inventaire local et interrogeable des déclarations et des dépendances résolues de projets Maven, avec accès aux preuves de collecte.

## Principes

1. Découvrir les POM à toute profondeur ; distinguer arborescence, agrégation et héritage.
2. Conserver le module et la configuration Maven associés à chaque résolution.
3. Distinguer l'introduction d'une dépendance de la gestion de sa version.
4. Utiliser Maven comme référence de résolution, via les wrappers des projets.
5. Respecter les dépôts et settings configurés pour la résolution des parents et BOM.
6. Conserver scopes, exclusions, profils déclarés et dépendances transitives.
7. Distinguer les déclarations des résultats Maven ; ne pas les présenter comme des observations runtime.
8. Produire des résultats partiels explicites lorsqu'une collecte échoue.
9. Ne pas réécrire les POM analysés.
10. Tester avec des fixtures entièrement fictives et des caches isolés.

## Questions prises en charge

- Quelles dépendances Maven sélectionne-t-il pour ce module ?
- Quels chemins les introduisent ?
- Quelle origine Maven documente-t-il pour une déclaration effective ?
- Quels modules consomment un composant également identifié dans les sources analysées ?

La reconstruction exhaustive des overrides et de la médiation des versions est une extension future, distincte de la collecte initiale.
