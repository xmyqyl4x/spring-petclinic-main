#!/bin/bash

URLS=(
  "http://localhost:8080/"
  "http://localhost:8080/owners/find"
  "http://localhost:8080/owners?lastName="
  "http://localhost:8080/owners?lastName=Black"
  "http://localhost:8080/oups"
  "http://localhost:8080/owners?lastName=Davis"
  "http://localhost:8080/owners?page=2"
  "http://localhost:8080/vets.html"
  "http://localhost:8080/oups"
  "http://localhost:8080/owners?page=1"
  "http://localhost:8080/vets.html?page=2"
  "http://localhost:8080/vets.html?page=1"
  "http://localhost:8080/owners/2"
  "http://localhost:8080/owners/2/edit"
  "http://localhost:8080/oups"
  "http://localhost:8080/owners/2/pets/new"
  "http://localhost:8080/owners/3/pets/4/visits/new"
  "http://localhost:8080/owners/5"
  "http://localhost:8080/oups"
  "http://localhost:8080/owners/5/edit"
  "http://localhost:8080/owners/3/pets/new"
  "http://localhost:8080/oups"
  "http://localhost:8080/owners/4/pets/5/visits/new"
)

for ((i = 1; i <= 50; i++)); do
  echo "=== Cycle $i of 50 ==="
  for url in "${URLS[@]}"; do
    curl -s -o /dev/null -w "%{http_code} %{url_effective}\n" "$url"
  done
  echo "=== Completed cycle $i ==="
  if ((i < 50)); then
    sleep 5
  fi
done
