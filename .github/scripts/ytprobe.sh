#!/bin/bash
# Diagnosa: apa yang dikembalikan YouTube ke pencarian dari server GitHub
mkdir -p shots
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
curl -sL -A "$UA" -H "Accept-Language: id-ID,id;q=0.9" -H "Cookie: CONSENT=YES+1; SOCS=CAI" \
  "https://www.youtube.com/results?hl=id&gl=ID&search_query=Ajaib+Kau+Tuhan+JPCC+Worship" -o yt.html
{
  echo "size: $(wc -c < yt.html)"
  for k in 'var ytInitialData' 'ytInitialData' videoRenderer lockupViewModel compactVideoRenderer videoWithContextRenderer "not a bot" consent.youtube; do
    echo "$k: $(grep -o "$k" yt.html | wc -l)"
  done
} > shots/ytprobe.txt
cat shots/ytprobe.txt
