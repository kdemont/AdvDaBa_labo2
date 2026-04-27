# Advanced Database - Laboratoire 2 - Chargement DBLP dans Neo4j

## Présentation du projet

Ce projet a été réalisé dans le cadre du cours **Advanced Database**.

L'objectif est de charger le dataset DBLP Citation Network dans Neo4j sous forme de graphe.

Le modèle de graphe implémenté est le suivant :

```text
(:AUTHOR {_id, name})-[:AUTHORED]->(:ARTICLE {_id, title})
(:ARTICLE)-[:CITES]->(:ARTICLE)
```

Le programme lit un fichier JSONL DBLP, crée les noeuds `ARTICLE` et `AUTHOR`, puis crée les relations `AUTHORED` et `CITES`.

---

## Structure du dépôt

Structure actuellement présente dans le dépôt :

```text
.
├── Dockerfile
├── docker-compose.yaml
├── build.sh
├── pom.xml
├── README.md
├── src/
│   └── main/java/mse/advDB/Example.java
├── neo4j_mount/
│   └── conf/
│       └── neo4j.conf
├── logs/
│   └── local/
│       ├── run_10000.log
│       ├── run_1M.log
│       ├── run_4M.log
│       ├── run_1M_optimised.log
│       ├── run_1M_optimised_batch5000.log
│       └── run_6M_optimised_batch5000.log
├── TP02 AdvDB.pdf
├── dblpExample.json
└── dblpExample.jsonl
```

Le dossier `logs/local/` contient les logs des tests réalisés localement avec Docker Compose.

---

## Remarque importante sur le dataset

Le dataset complet DBLP ne doit pas être commité dans Git.

Le loader permet de streamer directement le dataset depuis l'URL distante suivante :

```text
http://vmrum.isc.heia-fr.ch/files/DBLP-Citation-network-V18.jsonl
```

Un petit fichier de test peut également être utilisé :

```text
http://vmrum.isc.heia-fr.ch/files/test.jsonl
```

---

## Configuration

Le loader est configuré avec des variables d'environnement dans le fichier `docker-compose.yaml`.

Configuration actuelle du dépôt :

```yaml
environment:
	- JSON_FILE=http://vmrum.isc.heia-fr.ch/files/DBLP-Citation-network-V18.jsonl
	- MAX_NODES=6000000
	- BATCH_SIZE=5000
	- NEO4J_IP=172.24.0.10
```

| Variable | Description |
|---|---|
| `JSON_FILE` | Chemin local ou URL distante du fichier JSONL |
| `MAX_NODES` | Nombre maximal de lignes/articles à traiter |
| `BATCH_SIZE` | Taille des batchs envoyés à Neo4j |
| `NEO4J_IP` | Adresse IP du conteneur Neo4j |

---

## Build de l'image Docker

```bash
chmod +x build.sh
./build.sh
```

Le script `build.sh` construit l'image Docker de l'application Java/Maven.

---

## Lancement local avec Docker Compose

```bash
docker compose -f docker-compose.yaml up
```

Neo4j Browser est ensuite disponible à l'adresse suivante :

```text
http://localhost:7474
```

Identifiants Neo4j :

```text
username: neo4j
password: test
```

---

## Nettoyer la base Neo4j locale

La version optimisée du loader utilise `CREATE` pour les relations. Il faut donc impérativement repartir d'une base vide avant chaque nouveau test, afin d'éviter de créer des relations en double.

Commande utilisée pour nettoyer la base locale :

```bash
docker compose -f docker-compose.yaml down
docker run --rm -v "$PWD/neo4j_mount:/work" alpine sh -c "rm -rf /work/data /work/logs && mkdir -p /work/data /work/logs"
```

---

## Lancer un benchmark et sauvegarder les logs

Exemple pour lancer un test avec 1 million d'articles et conserver les logs :

```bash
docker compose -f docker-compose.yaml down
docker run --rm -v "$PWD/neo4j_mount:/work" alpine sh -c "rm -rf /work/data /work/logs && mkdir -p /work/data /work/logs"

./build.sh

docker compose -f docker-compose.yaml up 2>&1 | tee logs/local/run_1M_optimised_batch5000.log
```

---


## Résultats locaux

### Test initial avec 10'000 articles

Fichier de log :

```text
logs/local/run_10000.log
```

Résultat :

```text
Input article lines read: 10000
Articles loaded: 10000
Authors loaded: 31062
Total nodes loaded: 41062
AUTHORED relationships loaded: 32373
CITES relationships loaded: 277
Duration seconds: 12
```

---

### Test initial avec 1 million d'articles

Fichier de log :

```text
logs/local/run_1M.log
```

Résultat :

```text
Input article lines read: 1000000
Articles loaded: 1000000
Authors loaded: 1516347
Total nodes loaded: 2516347
AUTHORED relationships loaded: 3411998
CITES relationships loaded: 1616716
Duration seconds: 521
```

---

### Test initial avec 4 millions d'articles

Fichier de log :

```text
logs/local/run_4M.log
```

Résultat :

```text
Input article lines read: 4000000
Articles loaded: 4000000
Authors loaded: 3639993
Total nodes loaded: 7639993
AUTHORED relationships loaded: 13659841
CITES relationships loaded: 26018427
Duration seconds: 2957
```

Ce temps correspond à environ 49 minutes.

---

### Test optimisé avec 1 million d'articles

Fichier de log :

```text
logs/local/run_1M_optimised.log
```

Résultat :

```text
Input article lines read: 1000000
Articles loaded: 1000000
Authors loaded: 1516347
Total nodes loaded: 2516347
AUTHORED relationships loaded: 3411998
CITES relationships loaded: 1616716
Duration seconds: 487
```

---

### Test optimisé avec 1 million d'articles et batch size 5000

Fichier de log :

```text
logs/local/run_1M_optimised_batch5000.log
```

Résultat :

```text
Input article lines read: 1000000
Articles loaded: 1000000
Authors loaded: 1516347
Total nodes loaded: 2516347
AUTHORED relationships loaded: 3411998
CITES relationships loaded: 1616716
Duration seconds: 446
```

---

### Test optimisé avec 6 millions d'articles et batch size 5000

Fichier de log :

```text
logs/local/run_6M_optimised_batch5000.log

Résultat :

```text
Input article lines read: 6000000
Articles loaded: 6000000
Authors loaded: 4590309
Total nodes loaded: 10590309
AUTHORED relationships loaded: 20494932
CITES relationships loaded: 58378898
Duration seconds: 4759

---

## Comparaison des performances (résumé)

| Version | Batch size | Articles lus | Durée | Durée lisible | Nœuds | Relations |
|---|---:|---:|---:|---:|---:|---:|
| Version initiale 1M | 1000 | 1'000'000 | 521 s | 8 min 41 s | 2'516'347 | 5'028'714 |
| Relations optimisées 1M | 1000 | 1'000'000 | 487 s | 8 min 07 s | 2'516'347 | 5'028'714 |
| Relations optimisées + batch 5000 1M | 5000 | 1'000'000 | 446 s | 7 min 26 s | 2'516'347 | 5'028'714 |
| Version initiale 4M | 1000 | 4'000'000 | 2957 s | 49 min 17 s | 7'639'993 | 39'678'268 |
| Relations optimisées + batch 5000 6M | 5000 | 6'000'000 | 4759 s | 1 h 19 min 19 s | 10'590'309 | 78'873'830 |

La version optimisée conserve exactement le même graphe final tout en réduisant le temps de chargement.

Les logs locaux sont conservés dans `logs/local/`, car ils sont petits et utiles pour documenter les essais réalisés pendant le développement.

--- 

# Déploiement Kubernetes

Ce dossier contient les fichiers de configuration Kubernetes pour le projet de chargement DBLP dans Neo4j.

---

## Fichiers

- `neo4j-pvc.yaml` : volume persistant utilisé pour stocker les données Neo4j.
- `neo4j-deployment.yaml` : déploiement du pod Neo4j.
- `neo4j-service.yaml` : service interne exposant Neo4j Browser et Bolt.
- `loader-job.yaml` : job Kubernetes exécutant le loader DBLP.

---

## Image Docker du loader

L’image Docker du loader est publiée sur GitHub Container Registry :

```text
ghcr.io/florian-devenes/adbd-neo4j-loader:v1
```

Cette image est publique afin que Kubernetes puisse la télécharger sans `imagePullSecret`.

## Namespace Kubernetes

Avant de déployer les ressources, il faut se placer dans le namespace fourni pour le laboratoire.

Remplacer `<NAMESPACE>` par le namespace attribué au groupe :

```bash
kubectl config set-context --current --namespace=<NAMESPACE>
```

Vérifier le namespace courant :

```bash
kubectl config view --minify | grep namespace
```

---

## Ordre de déploiement

Déployer d’abord Neo4j et son volume persistant :

```bash
kubectl apply -f k8s/neo4j-pvc.yaml
kubectl apply -f k8s/neo4j-deployment.yaml
kubectl apply -f k8s/neo4j-service.yaml
```

Vérifier que les ressources ont été créées :

```bash
kubectl get pvc
kubectl get pods
kubectl get svc
```

Attendre que le pod Neo4j soit en état `Running`.

Afficher les logs du pod Neo4j :

```bash
kubectl logs -f deployment/neo4j
```

Une fois Neo4j démarré, lancer le job loader :

```bash
kubectl apply -f k8s/loader-job.yaml
```

Suivre les logs du loader :

```bash
kubectl logs -f job/dblp-loader
```

---

## Configuration du loader

La configuration du loader se trouve dans `k8s/loader-job.yaml`.

Variables utilisées :

| Variable | Description |
|---|---|
| `JSON_FILE` | URL ou chemin du fichier JSONL DBLP |
| `MAX_NODES` | Nombre maximal d’articles à lire |
| `BATCH_SIZE` | Taille des batchs envoyés à Neo4j |
| `NEO4J_IP` | Nom du service Kubernetes Neo4j |

---
### logs

Afficher les logs :

```bash
kubectl logs job/dblp-loader
```

Sauvegarder les logs dans un fichier local :

```bash
mkdir -p logs/k8s
kubectl logs job/dblp-loader > logs/k8s/k8s_loader.log
```

Ces logs peuvent ensuite être ajoutés au dépôt si nécessaire :

```bash
git add -f logs/k8s/k8s_loader.log
git commit -m "Add Kubernetes loader logs"
```

---

## Nettoyer le job loader

Avant de relancer un job avec le même nom, supprimer l’ancien job :

```bash
kubectl delete job dblp-loader
```

Cela supprime le job Kubernetes, mais ne supprime pas les données Neo4j stockées dans le volume persistant.

---

## Attention : base vide requise avant un nouvel import

La version optimisée du loader utilise `CREATE` pour créer les relations `AUTHORED` et `CITES`.

Cette optimisation améliore les performances, mais implique que la base Neo4j doit être vide avant chaque import complet.

Si le loader est exécuté plusieurs fois sur la même base, les relations risquent d’être créées en double.

Pour repartir d’une base vide, supprimer le déploiement Neo4j et le volume persistant :

```bash
kubectl delete job dblp-loader
kubectl delete deployment neo4j
kubectl delete pvc neo4j-data-pvc
```

Puis recréer Neo4j :

```bash
kubectl apply -f k8s/neo4j-pvc.yaml
kubectl apply -f k8s/neo4j-deployment.yaml
kubectl apply -f k8s/neo4j-service.yaml
```

Attendre que Neo4j soit `Running`, puis relancer le loader :

```bash
kubectl apply -f k8s/loader-job.yaml
kubectl logs -f job/dblp-loader
```

---
