# Bonheur Formations — version Android de préparation

## Situation

Cette branche est **indépendante de l'application Bonheur Services existante**, de Cantique Salutiste et du site web de démonstration. Le dépôt était vide avant ce travail. Ce code ne doit pas être présenté comme une mise à jour de l'application déjà distribuée.

- Identifiant de l'application de production **prévu**, non confirmé dans Play Console : `com.bonheur.formations`.
- L'APK de debug utilise `com.bonheur.formations.dev` pour ne jamais remplacer une application installée.
- Android : `compileSdk=36`, `targetSdk=36`, `minSdk=26`.
- Google Play Billing : `9.1.0` (récupération des fiches produits et des tarifs seulement).
- **Aucun achat n'est possible dans cette branche.** Le bouton est explicitement désactivé jusqu'à mise en place du contrôle serveur. Aucune transaction ne doit être simulée comme réussie.
- Aucun fichier de signature de production ni secret n'est dans GitHub.

## Catalogue dynamique attendu

Renseigner à la compilation une URL publique HTTPS qui répond avec JSON (sans exposer l'interface d'administration) :

```bash
gradle :app:assembleDebug -PBONHEUR_CATALOG_URL=https://api.example.org/public/offers
```

Exemple de **format**, pas de produits réels ou de prix fixes :

```json
{
  "offers": [
    {
      "title": "Intitulé défini par l’administrateur",
      "description": "Présentation",
      "playProductId": "identifiant_valide_de_play_console",
      "playProductType": "inapp"
    }
  ]
}
```

`playProductType` : `inapp` pour achat unique ; `subs` pour abonnement. L'interface lit les noms et descriptions du catalogue. **Les prix d'achats numériques doivent provenir de Google Play Console, pas du texte modifiable librement dans la base de données.** Les prix éventuellement configurés par l'administrateur doivent être synchronisés avec les produits/plan Play avant affichage.

### Travail serveur et Google Play encore nécessaire

1. Vérifier l'accès administrateur avec Google Sign-In ou une authentification serveur : autoriser `patientmundele17@gmail.com` côté **serveur**, jamais uniquement dans le téléphone.
2. Relier réellement le catalogue existant de « Bonheur — Offres numériques » à un endpoint HTTPS authentifié en écriture et lisible publiquement en lecture. Le code du projet hébergé dans ChatGPT Sites n'était pas accessible par le connecteur GitHub.
3. Définir les produits et forfaits dans Play Console, puis tester la récupération des références et tarifs sur un appareil Play Store sous un compte testeur.
4. Implémenter une validation serveur des `purchaseToken` via Google Play Developer API, reliée à un utilisateur authentifié, avec stockage idempotent, respect des états PENDING/PURCHASED et notifications de révocation et d'expiration.
5. Faire un **acknowledge** seulement après validation d'un achat PURCHASED ; restaurer les droits à l'ouverture et à la reprise, traiter les renouvellements et les remboursements.
6. Tester les achats et renouvellements avec des cartes de test et un environnement interne ; les paiements réels doivent rester bloqués avant ces validations.
7. Signer le `.aab` de production avec une clé de téléversement conservée hors Git, vérifier l'identifiant `com.bonheur.formations` dans la fiche Play, puis seulement mettre la version en test interne. Le debug `.aab` généré par CI **n'est pas une livraison Play Console**.

## Construire et tester

GitHub Actions > « Android verification (no production release) » compile un **APK de test** et un **AAB de debug**. Les artefacts sont téléchargeables dans l'exécution, mais ne remplacent pas le fichier AAB de production signé.

Ne pas fusionner cette branche dans `main` avant la validation explicite du service catalogue, de la connexion administrateur, des paiements et des tests Play.

## Références

- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/google/play/billing/integrate
- https://support.google.com/googleplay/android-developer/answer/10281818
