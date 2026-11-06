"""A CORUM Life payload shaped exactly like `/api/contract/life/{code}`.

The keys, nesting, nulls and number types follow a masked live payload; every
figure is invented and no live account data belongs in the repo. The shape
details that matter to the parser are kept on purpose:

- three pockets, with CC, XL and EU in both "Gestion libre" and
  "Corum Life Horizon";
- the euro fund (FGCORUM) under chartSections `euroLife`, with an int
  quantity/unitPrice/percent (0 here, so a parser that reads them is caught)
  and a null valorisationDate;
- `reallocationAmount` null on most lines and a number on some;
- a switch out of the euro fund into a bond fund, shaped like the live one:
  the euro fund line carries a negative `reallocationAmount`, the bond line
  (CBEHY) the matching positive one and no net invested of its own, so the
  lines' net invested sum (3315) falls short of the contract's (3480) and the
  bond line alone would show a gain it never made;
- `contractDetail.valuationAmount - netInvestedAmount == gainOrLoss`.

Not a `test_*.py` module, so discovery imports it only through the tests.
"""

import copy

LIFE_CODE = "000123"


def _line(code, quantity, unit_price, amount, net, date, reallocation=None):
    return {
        "productName": code,
        "percent": 10.0,
        "amount": amount,
        "quantity": quantity,
        "unitPrice": unit_price,
        "valorisationDate": date,
        "netInvestedAmountCumulated": net,
        "reallocationAmount": reallocation,
    }


def _chart(code, amount):
    return {"productName": code, "percent": 10.0, "amount": amount}


_PAYLOAD = {
    "contractType": "LIFE",
    "contractCode": LIFE_CODE,
    "coSubscriber": None,
    "creationDate": "15/01/2024",
    "valuationDate": "04/11/2026",
    "contractDetail": {
        "grossInvestedAmount": 3540,
        "netInvestedAmount": 3480.0,
        "valuationAmount": 3506.56,
        "gainOrLoss": 26.56,
        "displayLifeHistory": True,
        "isDeducedFromTax": None,
        "isNewContract": False,
        "grossInitialAmount": 1000,
        "grossOneOffAmount": 1200,
        "totalGrossProgramSavingAmount": 1200,
        "programSavingAmount": {"amount": 50, "frequency": "monthly"},
    },
    "contractProfile": {
        "investmentProfile": "Gestion libre",
        "isManagedInvestmentProfile": False,
        "chartSections": [
            {
                "sectionName": "bondFund",
                "sectionPercentage": 7.9,
                "sectionProduct": [
                    _chart("CBEHY", 105.0),
                    _chart("BCO", 100.0),
                    _chart("CBE", 72.0),
                ],
            },
            {
                "sectionName": "realEstate",
                "sectionPercentage": 56.9,
                "sectionProduct": [
                    _chart("CC", 630.0),
                    _chart("EU", 700.0),
                    _chart("XL", 665.0),
                ],
            },
            {
                "sectionName": "euroLife",
                "sectionPercentage": 35.2,
                "sectionProduct": [_chart("FGCORUM", 1234.56)],
            },
        ],
        "distributionTableSections": [
            {
                "sectionPercentage": 43.5,
                "sectionProduct": [
                    _line("CC", 2.5, 180.0, 450.0, 440.0, "31/10/2026"),
                    _line("XL", 3.0, 190.0, 570.0, 560.0, "31/10/2026"),
                    # Bought only by the switch: no net invested of its own.
                    _line("CBEHY", 10.0, 10.5, 105.0, 0.0, "30/10/2026", 100.0),
                    _line("BCO", 4.0, 25.0, 100.0, 98.0, "30/10/2026"),
                    _line("EU", 1.5, 200.0, 300.0, 290.0, "31/10/2026"),
                ],
                "profileName": "Gestion libre",
                "netInvestedAmountCumulated": 1388.0,
                "amount": 1525.0,
                "performance": 2.5,
            },
            {
                "sectionPercentage": 21.3,
                "sectionProduct": [
                    _line("CC", 1.0, 180.0, 180.0, 175.0, "31/10/2026"),
                    # A later NAV date than the same fund in the other pocket.
                    _line("XL", 0.5, 190.0, 95.0, 92.0, "03/11/2026"),
                    _line("EU", 2.0, 200.0, 400.0, 390.0, "31/10/2026"),
                    _line("CBE", 6.0, 12.0, 72.0, 70.0, "30/10/2026"),
                ],
                "profileName": "Corum Life Horizon",
                "netInvestedAmountCumulated": 727.0,
                "amount": 747.0,
                "performance": 2.7,
            },
            {
                "sectionPercentage": 35.2,
                "sectionProduct": [
                    {
                        "productName": "FGCORUM",
                        "percent": 100,
                        "amount": 1234.56,
                        "quantity": 0,
                        "unitPrice": 0,
                        "valorisationDate": None,
                        "netInvestedAmountCumulated": 1200.0,
                        # The switch out, towards CBEHY.
                        "reallocationAmount": -100.0,
                    }
                ],
                "profileName": "CORUM Eurolife",
                "netInvestedAmountCumulated": 1200.0,
                "amount": 1234.56,
                "performance": 2.9,
            },
        ],
        "totalInvestedAmount": 3315.0,
        "totalPerformance": 2.7,
    },
    "contractDeathClose": {
        "deathClose": "Clause type",
        "deathCloseText": None,
        "beneficiaries": None,
    },
    "valuationData": [
        {
            "date": "31/10/2026",
            "investedAmount": 3400,
            "saving": {"rawSaving": 3500, "totalSaving": 3500},
            "fees": 0,
            "withdrawal": 0,
        }
    ],
}


def set_total(payload, total):
    """Move the contract total and keep CORUM's own gain in step with it, so
    only the check under test can fail."""
    detail = payload["contractDetail"]
    detail["valuationAmount"] = total
    detail["gainOrLoss"] = round(total - detail["netInvestedAmount"], 2)


def life_payload():
    """A fresh copy each call: the tests mutate it to build their failures."""
    return copy.deepcopy(_PAYLOAD)


def pocket(payload, name):
    """The pocket whose `profileName` is `name`."""
    for section in payload["contractProfile"]["distributionTableSections"]:
        if section["profileName"] == name:
            return section
    raise KeyError(name)


def line(payload, pocket_name, code):
    """The `code` line inside the pocket named `pocket_name`."""
    for product in pocket(payload, pocket_name)["sectionProduct"]:
        if product["productName"] == code:
            return product
    raise KeyError(code)


def _op(op_type, category, date, amount, op_id, funds=None):
    return {
        "operationType": op_type,
        "operationCategory": category,
        "date": date,
        "amount": amount,
        # Live, CORUM sends the id as a string of digits.
        "operationIds": [str(op_id)],
        "fundCodes": funds,
    }


# `/api/operations/`, shaped like a masked live payload: every contract of the
# login (the real-estate one too), each split by year, newest year first. One
# entry per operation type seen live; ids, dates and amounts are invented.
_OPERATIONS = [
    {
        "contractCode": "RE-1",
        "contractType": "REAL_ESTATE",
        "yearData": [
            {"year": "2026", "operations": [
                _op("dividend", "dividend", "10/10/2026", 12.5, 900001, "US"),
            ]},
        ],
    },
    {
        "contractCode": LIFE_CODE,
        "contractType": "LIFE",
        "yearData": [
            {"year": "2026", "operations": [
                _op("couponDetachment", "couponDetachment", "31/10/2026", 3.21, 700010, "CC"),
                _op("scheduledPayment", "progressiveSavings", "05/10/2026", 50, 700009),
                _op("administrativeManagementCosts", "administrativeManagementCosts",
                    "30/09/2026", -1.42, 700008),
                # The two legs of one switch, as CORUM lists them.
                _op("arbitrage", "arbitrage", "15/06/2026", -100.0, 700007),
                _op("arbitrage", "arbitrage", "15/06/2026", 100.0, 700006),
            ]},
            {"year": "2025", "operations": [
                _op("profitParticipation", "profitParticipation", "31/12/2025", 18.4, 700005),
                _op("couponDetachment", "couponDetachment", "30/11/2025", 2.75, 700004, "eu, XL"),
            ]},
            {"year": "2024", "operations": [
                _op("subscription", "subscriptionAndPayment", "15/01/2024", 1000, 700001),
            ]},
        ],
    },
]


def operations_payload():
    """A fresh copy each call, like `life_payload`."""
    return copy.deepcopy(_OPERATIONS)
