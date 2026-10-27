FROM nginx:alpine
RUN rm -rf /usr/share/nginx/html/*
COPY index.html /usr/share/nginx/html/
COPY faq.html /usr/share/nginx/html/
COPY privacy.html /usr/share/nginx/html/
COPY terms.html /usr/share/nginx/html/
COPY acknowledgements.html /usr/share/nginx/html/
COPY photocast.css /usr/share/nginx/html/
COPY viewer.js /usr/share/nginx/html/
COPY home.js /usr/share/nginx/html/
COPY shot-map.webp /usr/share/nginx/html/
COPY shot-detail.webp /usr/share/nginx/html/
COPY shot-ask.webp /usr/share/nginx/html/
COPY field-map.webp /usr/share/nginx/html/
COPY field-decide.webp /usr/share/nginx/html/
COPY field-steel-rigg.webp /usr/share/nginx/html/
COPY favicon.svg /usr/share/nginx/html/
COPY favicon.png /usr/share/nginx/html/
COPY apple-touch-icon.png /usr/share/nginx/html/
COPY screenshot.png /usr/share/nginx/html/
COPY screenshot-map.png /usr/share/nginx/html/
COPY screenshot-popup.png /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
# Cloudflare and browsers cache css/js/images for 30 days under fixed URLs (nginx.conf), so a
# changed file would never be re-fetched. Give every asset a content-fingerprinted URL: rewrite each
# exact quoted reference "file" in the pages to "file?v=<10 hex of md5>". The source HTML keeps plain
# URLs, so local previews are unaffected; the plain paths are still served, bytes unchanged.
RUN cd /usr/share/nginx/html && \
    for f in *.css *.js *.webp *.png *.svg; do \
        [ -f "$f" ] || continue; \
        h=$(md5sum "$f" | cut -c1-10); \
        e=$(printf '%s' "$f" | sed 's/[.]/\\./g'); \
        sed -i "s|\"$e\"|\"$f?v=$h\"|g" *.html; \
    done

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
