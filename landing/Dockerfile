FROM nginx:alpine
RUN rm -rf /usr/share/nginx/html/*
COPY index.html /usr/share/nginx/html/
COPY faq.html /usr/share/nginx/html/
COPY privacy.html /usr/share/nginx/html/
COPY terms.html /usr/share/nginx/html/
COPY acknowledgements.html /usr/share/nginx/html/
COPY favicon.png /usr/share/nginx/html/
COPY logo.png /usr/share/nginx/html/
COPY screenshot.png /usr/share/nginx/html/
COPY screenshot-map.png /usr/share/nginx/html/
COPY screenshot-popup.png /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
