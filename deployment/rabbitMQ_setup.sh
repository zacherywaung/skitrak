# Run on the RabbitMQ EC2 instance (Amazon Linux 2023)

sudo dnf install -y docker
sudo systemctl enable --now docker

sudo docker run -d --name rabbitmq --restart unless-stopped \
  -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=skitrak \
  -e RABBITMQ_DEFAULT_PASS=<RABBIT_PASSWORD> \
  rabbitmq:3.13-management