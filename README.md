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
