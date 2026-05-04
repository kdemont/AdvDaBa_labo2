# Advanced Database - Laboratoire 2 - Chargement DBLP dans Neo4j

## Présentation du projet

Ce projet a été réalisé dans le cadre du cours **Advanced Database**.

L'objectif est de charger le dataset **DBLP Citation Network** dans Neo4j sous forme de graphe.

Le modèle de graphe implémenté est le suivant :

```text
(:AUTHOR {_id, name})-[:AUTHORED]->(:ARTICLE {_id, title})
(:ARTICLE)-[:CITES]->(:ARTICLE)
```

Le programme lit un fichier JSONL DBLP, crée les nœuds `ARTICLE` et `AUTHOR`, puis crée les relations `AUTHORED` et `CITES`.

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
├── archive/
│   └── anciennes versions du loader
├── k8s/
│   ├── neo4j-pvc.yaml
│   ├── neo4j-deployment.yaml
│   ├── neo4j-service.yaml
│   ├── loader-job.yaml
│   └── loader-job_batch_steaming.yaml
├── neo4j_mount/
│   └── conf/
│       └── neo4j.conf
├── logs/
│   ├── local/
│   │   ├── run_10000.log
│   │   ├── run_1M.log
│   │   ├── run_4M.log
│   │   ├── run_1M_optimised.log
│   │   ├── run_1M_optimised_batch5000.log
│   │   └── run_6M_optimised_batch5000.log
│   └── k8s/
│       └── logs Kubernetes des essais et du run final
├── TP02 AdvDB.pdf
├── dblpExample.json
└── dblpExample.jsonl
```

Les dossiers `logs/local/` et `logs/k8s/` contiennent les logs des tests réalisés.

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

## Configuration locale

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
```

Résultat :

```text
Input article lines read: 6000000
Articles loaded: 6000000
Authors loaded: 4590309
Total nodes loaded: 10590309
AUTHORED relationships loaded: 20494932
CITES relationships loaded: 58378898
Duration seconds: 4759
```

---

## Comparaison des performances locales

| Version | Batch size | Articles lus | Durée | Durée lisible | Nœuds | Relations |
|---|---:|---:|---:|---:|---:|---:|
| Version initiale 1M | 1000 | 1'000'000 | 521 s | 8 min 41 s | 2'516'347 | 5'028'714 |
| Relations optimisées 1M | 1000 | 1'000'000 | 487 s | 8 min 07 s | 2'516'347 | 5'028'714 |
| Relations optimisées + batch 5000 1M | 5000 | 1'000'000 | 446 s | 7 min 26 s | 2'516'347 | 5'028'714 |
| Version initiale 4M | 1000 | 4'000'000 | 2957 s | 49 min 17 s | 7'639'993 | 39'678'268 |
| Relations optimisées + batch 5000 6M | 5000 | 6'000'000 | 4759 s | 1 h 19 min 19 s | 10'590'309 | 78'873'830 |

La version optimisée conserve le même modèle de graphe final tout en réduisant le temps de chargement.

Les logs locaux sont conservés dans `logs/local/`, car ils sont petits et utiles pour documenter les essais réalisés pendant le développement.

---

# Déploiement Kubernetes

Le déploiement Kubernetes contient :

```text
1 pod Neo4j contenant les données chargées
1 pod loader responsable de l'insertion des données
```

Namespace utilisé :

```text
dev-dem-adv-daba-26
```

---

## Fichiers Kubernetes

- `neo4j-pvc.yaml` : volume persistant utilisé pour stocker les données Neo4j.
- `neo4j-deployment.yaml` : déploiement du pod Neo4j.
- `neo4j-service.yaml` : service interne exposant Neo4j Browser et Bolt.
- `loader-job.yaml` : job Kubernetes standard du loader.
- `loader-job_batch_steaming.yaml` : job utilisant la version avec lecture HTTP par blocs.

---

## Image Docker du loader

L’image Docker du loader est publiée sur GitHub Container Registry :

```text
ghcr.io/florian-devenes/adbd-neo4j-loader:v5
```

Cette image est publique afin que Kubernetes puisse la télécharger sans `imagePullSecret`.

Cette image contient :

- lecture HTTP par blocs avec `Range requests` ;
- cache mémoire temporaire par chunk ;
- `CREATE` sur base vide pour accélérer l'import ;
- déduplication des auteurs côté Java avec `HashSet` ;
- batch size Kubernetes à `10000` ;
- logs détaillés du temps total, des retries HTTP et des chunks lus.

---

## Configuration Kubernetes du loader

La configuration du loader Kubernetes est définie dans `k8s/loader-job_batch_steaming.yaml`.

```yaml
env:
  - name: JSON_FILE
    value: "http://vmrum.isc.heia-fr.ch/files/DBLP-Citation-network-V18.jsonl"
  - name: MAX_NODES
    value: "6000000"
  - name: BATCH_SIZE
    value: "10000"
  - name: HTTP_CHUNK_SIZE_MB
    value: "16"
  - name: NEO4J_IP
    value: "neo4j"
```

| Variable | Description |
|---|---|
| `JSON_FILE` | URL du fichier DBLP JSONL |
| `MAX_NODES` | Nombre maximal d’articles à lire |
| `BATCH_SIZE` | Taille des batchs envoyés à Neo4j |
| `HTTP_CHUNK_SIZE_MB` | Taille des blocs HTTP temporaires chargés en mémoire |
| `NEO4J_IP` | Nom du service Kubernetes Neo4j |

---

## Lecture HTTP par blocs

Les premiers essais Kubernetes ont montré que les connexions HTTP longues vers le fichier DBLP complet pouvaient être interrompues par des erreurs du type :

```text
Premature EOF
Connection reset
```

Pour stabiliser la lecture, le loader utilise une lecture HTTP par blocs avec des requêtes `Range`.

Le fichier complet n’est jamais copié dans le conteneur. Seuls des blocs temporaires de 16 MB sont chargés en mémoire, traités, puis libérés.

Exemple conceptuel :

```text
GET bytes=0-16777215
GET bytes=16777216-33554431
GET bytes=33554432-50331647
...
```

Cette approche respecte la contrainte du laboratoire : les données sont toujours streamées depuis l’URL officielle.

---

## Namespace Kubernetes

Avant de déployer les ressources, il faut se placer dans le namespace fourni pour le laboratoire.

```bash
kubectl config set-context --current --namespace=dev-dem-adv-daba-26
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
kubectl apply -f k8s/loader-job_batch_steaming.yaml
```

Suivre les logs du loader :

```bash
kubectl logs -f job/dblp-loader --tail=80
```

Afficher les derniers logs sans suivre en continu :

```bash
kubectl logs job/dblp-loader --tail=100
```

---

## Sauvegarder les logs Kubernetes

```bash
mkdir -p logs/k8s

kubectl logs job/dblp-loader > logs/k8s/k8s_loader_final_batch_streaming.log
kubectl get pods -o wide > logs/k8s/k8s_pods_final.txt
kubectl get jobs -o wide > logs/k8s/k8s_jobs_final.txt
kubectl get pvc -o wide > logs/k8s/k8s_pvc_final.txt
kubectl get svc -o wide > logs/k8s/k8s_services_final.txt
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

La version optimisée du loader utilise `CREATE` pour créer les nœuds et les relations sur une base vide.

Cette optimisation améliore les performances, mais implique que la base Neo4j doit être vide avant chaque import complet.

Si le loader est exécuté plusieurs fois sur la même base, des doublons peuvent être créés.

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
kubectl apply -f k8s/loader-job_batch_steaming.yaml
kubectl logs -f job/dblp-loader --tail=80
```

---

## Vérifications Neo4j

Ouvrir un port-forward :

```bash
kubectl port-forward svc/neo4j-svc 7474:7474 7687:7687
```

Puis ouvrir Neo4j Browser :

```text
http://localhost:7474
```

Identifiants :

```text
neo4j / test
```
---

## Informations utiles pour le rapport

Namespace :

```text
dev-dem-adv-daba-26
```

Credentials Neo4j :

```text
neo4j / test
```

Image loader :

```text
ghcr.io/florian-devenes/adbd-neo4j-loader:v5
```

Les IDs des pods peuvent être obtenus avec :

```bash
kubectl get pods
```

Les logs du loader peuvent être obtenus avec :

```bash
kubectl logs job/dblp-loader
```

Les logs affichent explicitement :

```text
Start time
End time
Duration seconds
HTTP retry count
HTTP chunk count
HTTP retry wait seconds
Effective loading seconds excluding HTTP retry waits
Input article lines read
Articles loaded
Authors loaded
Total nodes loaded
AUTHORED relationships loaded
CITES relationships loaded
```

Ces informations permettent de justifier le temps de chargement et le volume de données chargé.

---
