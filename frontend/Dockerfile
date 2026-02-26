# Frontend: Nginx server for React static files
FROM nginx:alpine

# Copy built React app to Nginx
COPY dist /usr/share/nginx/html

# Nginx config will be mounted via docker-compose volume

EXPOSE 80
