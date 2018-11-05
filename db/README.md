# MySQL Database

## Overview

### Required Environment Variables
- MYSQL_RANDOM_ROOT_PASSWORD: leave this as "yes"
- MYSQL_DATABASE : what to call the database for the app
- MYSQL_APP_USERNAME 
- MYSQL_APP_PASSWORD

### Run Docker Container
```
docker-compose up -d
```

### View Logs
```
docker-compose logs -f -t
```

### Down
```
docker-compose down
```