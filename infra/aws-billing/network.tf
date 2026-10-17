resource "aws_vpc" "billing" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = var.name }
}
resource "aws_internet_gateway" "billing" {
  vpc_id = aws_vpc.billing.id
}
resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.billing.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public-${count.index + 1}" }
}
resource "aws_subnet" "application" {
  count             = 2
  vpc_id            = aws_vpc.billing.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  availability_zone = local.azs[count.index]
  tags              = { Name = "${var.name}-app-${count.index + 1}" }
}
resource "aws_subnet" "data" {
  count             = 3
  vpc_id            = aws_vpc.billing.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 20)
  availability_zone = local.azs[count.index]
  tags              = { Name = "${var.name}-data-${count.index + 1}" }
}
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.billing.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.billing.id
  }
}
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
resource "aws_eip" "nat" {
  count  = 2
  domain = "vpc"
}
resource "aws_nat_gateway" "billing" {
  count         = 2
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  depends_on    = [aws_internet_gateway.billing]
}
resource "aws_route_table" "application" {
  count  = 2
  vpc_id = aws_vpc.billing.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.billing[count.index].id
  }
}
resource "aws_route_table_association" "application" {
  count          = 2
  subnet_id      = aws_subnet.application[count.index].id
  route_table_id = aws_route_table.application[count.index].id
}
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.billing.id
  route  = []
  # Intentionally no Internet/NAT route for database/broker subnets.
}
resource "aws_route_table_association" "data" {
  count          = 3
  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.billing.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.application[*].id
}
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}
resource "aws_security_group" "alb" {
  name_prefix = "${var.name}-alb-"
  description = "Only CloudFront origin-facing HTTPS; listener additionally requires a generated origin header."
  vpc_id      = aws_vpc.billing.id
}
resource "aws_vpc_security_group_ingress_rule" "cloudfront_https" {
  security_group_id = aws_security_group.alb.id
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_security_group" "application" {
  for_each    = local.services
  name_prefix = "${var.name}-${each.key}-"
  vpc_id      = aws_vpc.billing.id
  description = "Private ECS tasks; only ALB business and management health traffic enters."
}
resource "aws_vpc_security_group_ingress_rule" "application" {
  for_each                     = local.services
  security_group_id            = aws_security_group.application[each.key].id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}
resource "aws_vpc_security_group_ingress_rule" "health" {
  for_each                     = local.services
  security_group_id            = aws_security_group.application[each.key].id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 9090
  to_port                      = 9090
}
resource "aws_vpc_security_group_egress_rule" "alb_application" {
  for_each                     = local.services
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.application[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}
resource "aws_vpc_security_group_egress_rule" "alb_health" {
  for_each                     = local.services
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.application[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = 9090
  to_port                      = 9090
}
resource "aws_vpc_security_group_egress_rule" "application_https" {
  for_each          = local.services
  security_group_id = aws_security_group.application[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "AWS APIs, ECR and billing provider HTTPS through same-AZ NAT. No inbound public task access."
}
resource "aws_security_group" "database" {
  name_prefix = "${var.name}-database-"
  vpc_id      = aws_vpc.billing.id
}
resource "aws_vpc_security_group_ingress_rule" "database" {
  for_each                     = local.services
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.application[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_vpc_security_group_egress_rule" "application_database" {
  for_each                     = local.services
  security_group_id            = aws_security_group.application[each.key].id
  referenced_security_group_id = aws_security_group.database.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_security_group" "kafka" {
  name_prefix = "${var.name}-kafka-"
  vpc_id      = aws_vpc.billing.id
}
resource "aws_vpc_security_group_ingress_rule" "kafka_client" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.application["billing"].id
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
}
resource "aws_vpc_security_group_egress_rule" "payment_kafka" {
  security_group_id            = aws_security_group.application["billing"].id
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
}
resource "aws_vpc_security_group_ingress_rule" "kafka_brokers" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "-1"
  description                  = "MSK inter-broker/controller traffic within this cluster only."
}
resource "aws_vpc_security_group_egress_rule" "kafka_brokers" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.kafka.id
  ip_protocol                  = "-1"
}
