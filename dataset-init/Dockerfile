FROM python:3.12-alpine
COPY scripts/fetch_dataset.py /app/fetch_dataset.py
ENV DATASET_DIR=/data
ENTRYPOINT ["python3", "-u", "/app/fetch_dataset.py"]
